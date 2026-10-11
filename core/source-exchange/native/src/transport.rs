use anyhow::{bail, ensure, Context, Result};
use iroh::{address_lookup::memory::MemoryLookup, endpoint::{presets, Connection, QuicTransportConfig}, protocol::{AcceptError, ProtocolHandler, Router}, Endpoint};
use iroh_blobs::{
    api::proto::BlobStatus,
    get::fsm::{self, BlobContentNext, ConnectedNext, EndBlobNext},
    protocol::GetRequest,
    hashseq::HashSeq,
    store::fs::FsStore,
    ticket::BlobTicket,
    provider::events::{AbortReason, EventMask, EventSender, ObserveMode, ProviderMessage, RequestMode},
    BlobFormat, BlobsProtocol, Hash,
};
use serde::{Deserialize, Serialize};
use std::{path::{Path, PathBuf}, sync::{Arc, RwLock}, time::Duration};
use tokio::{sync::{watch, Semaphore}, task::JoinHandle};

use crate::ticket::{encode_fd2_ticket, ShareTicket};

pub const MAX_SOURCE_BYTES: u64 = 128 * 1024 * 1024;
const MAX_COLLECTION_FILES: usize = 32;
const MAX_METADATA_BYTES: u64 = 64 * 1024;
const IDLE_TIMEOUT: Duration = Duration::from_secs(25);

/// Bound accepted connections and streams, even before an application request arrives.
#[derive(Clone, Debug)]
struct SourceProvider {
    inner: BlobsProtocol,
    connections: Arc<Semaphore>,
}
impl ProtocolHandler for SourceProvider {
    async fn accept(&self, connection: Connection) -> std::result::Result<(), AcceptError> {
        let Ok(_permit) = self.connections.clone().try_acquire_owned() else {
            connection.close(1u32.into(), b"busy");
            return Ok(());
        };
        // The foreground session has a shorter overall lifetime in Android as well.
        let result = tokio::time::timeout(Duration::from_secs(15 * 60), self.inner.accept(connection.clone())).await;
        connection.close(0u32.into(), b"session ended");
        result.unwrap_or(Ok(()))
    }
    // Exchange owns the store lifecycle. Router must cancel peer handlers before
    // their file handles can be flushed; shutting the store here reverses that order.
}

fn source_events(allowed: Arc<RwLock<Option<Hash>>>) -> (EventSender, JoinHandle<()>) {
    let mask = EventMask { get: RequestMode::Intercept, get_many: RequestMode::Disabled,
        observe: ObserveMode::Intercept, ..EventMask::DEFAULT };
    let (events, mut incoming) = EventSender::channel(16, mask);
    let task = tokio::spawn(async move {
        let mut requests = 0u32;
        while let Some(event) = incoming.recv().await {
            match event {
                ProviderMessage::GetRequestReceived(message) => {
                    requests = requests.saturating_add(1);
                    let permitted = requests <= 32 && message.request.ranges.is_blob() &&
                        allowed.read().ok().is_some_and(|hash| *hash == Some(message.request.hash));
                    let _ = message.tx.send(if permitted { Ok(()) } else { Err(AbortReason::Permission) }).await;
                }
                ProviderMessage::ObserveRequestReceived(message) => {
                    let _ = message.tx.send(Err(AbortReason::Permission)).await;
                }
                _ => {},
            }
        }
    });
    (events, task)
}

#[derive(Deserialize)]
struct CollectionMetadata {
    header: [u8; 13],
    #[serde(deserialize_with = "bounded_names")]
    names: Vec<String>,
}

fn bounded_names<'de, D: serde::Deserializer<'de>>(deserializer: D) -> std::result::Result<Vec<String>, D::Error> {
    struct Names;
    impl<'de> serde::de::Visitor<'de> for Names {
        type Value = Vec<String>;
        fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result { formatter.write_str("a bounded source collection") }
        fn visit_seq<A: serde::de::SeqAccess<'de>>(self, mut sequence: A) -> std::result::Result<Self::Value, A::Error> {
            if sequence.size_hint().is_some_and(|size| size > MAX_COLLECTION_FILES) {
                return Err(serde::de::Error::custom("Too many source files"));
            }
            let mut names = Vec::new();
            while let Some(name) = sequence.next_element::<String>()? {
                if names.len() == MAX_COLLECTION_FILES || validate_name(&name).is_err() {
                    return Err(serde::de::Error::custom("Invalid source collection"));
                }
                names.push(name);
            }
            Ok(names)
        }
    }
    deserializer.deserialize_seq(Names)
}

#[derive(Debug, Clone, Serialize)]
pub struct ReceivedSource {
    /// Display-only metadata. Never joined to an output path.
    pub name: String,
    pub path: PathBuf,
    pub bytes: u64,
    pub hash: String,
}

/// Each sharing session has its own disposable store. Receiving never starts a provider.
/// Thus received files and unrelated cached data cannot silently become available to peers.
pub struct Exchange {
    endpoint: Endpoint,
    lookup: MemoryLookup,
    store: FsStore,
    router: Option<Router>,
    allowed: Arc<RwLock<Option<Hash>>>,
    events: Option<JoinHandle<()>>,
    internet: bool,
}

impl Exchange {
    pub async fn open(store_dir: PathBuf, sharing: bool) -> Result<Self> {
        Self::open_scoped(store_dir, sharing, true).await
    }

    pub async fn open_scoped(store_dir: PathBuf, sharing: bool, internet: bool) -> Result<Self> {
        let store = FsStore::load(store_dir).await?;
        let lookup = MemoryLookup::new();
        // A fresh endpoint identity is created per session. Nothing advertises device names.
        let builder = if internet { Endpoint::builder(presets::N0) } else { Endpoint::builder(presets::Minimal) };
        let transport = QuicTransportConfig::builder()
            .max_concurrent_bidi_streams(4u32.into()).max_concurrent_uni_streams(0u32.into())
            .max_idle_timeout(Some(IDLE_TIMEOUT.try_into()?)).build();
        let endpoint = builder.transport_config(transport).address_lookup(lookup.clone()).bind().await?;
        let allowed = Arc::new(RwLock::new(None));
        let (router, events) = if sharing {
            let (events, task) = source_events(allowed.clone());
            let provider = SourceProvider { inner: BlobsProtocol::new(&store, Some(events)), connections: Arc::new(Semaphore::new(4)) };
            (Some(Router::builder(endpoint.clone()).accept(iroh_blobs::ALPN, provider).spawn()), Some(task))
        } else { (None, None) };
        Ok(Self { endpoint, lookup, store, router, allowed, events, internet })
    }

    pub async fn share(&self, source: &Path, name: &str) -> Result<String> {
        ensure!(self.router.is_some(), "Not a sharing session");
        validate_name(name)?;
        let meta = tokio::fs::symlink_metadata(source).await?;
        ensure!(meta.is_file() && !meta.file_type().is_symlink() && meta.len() <= MAX_SOURCE_BYTES,
            "Select a bounded regular source file");
        // Copying freezes this share rather than serving a mutable external path.
        let added = self.store.blobs().add_path(source).await?;
        let measured = self.complete_size(added.hash).await?;
        ensure!(measured <= MAX_SOURCE_BYTES, "Source file exceeds limit");
        {
            let mut allowed = self.allowed.write().map_err(|_| anyhow::anyhow!("Source unavailable"))?;
            ensure!(allowed.is_none(), "A session shares only one immutable source");
            *allowed = Some(added.hash);
        }
        if self.internet {
            let _ = tokio::time::timeout(Duration::from_secs(8), self.endpoint.online()).await;
        }
        let ticket = BlobTicket::new(self.endpoint.addr(), added.hash, BlobFormat::Raw);
        Ok(encode_fd2_ticket(&ticket, name, measured)?)
    }

    pub async fn receive(
        &self,
        encoded: &str,
        output: &Path,
        cancel: &mut watch::Receiver<bool>,
        progress: impl Fn(u64) + Send + Sync,
    ) -> Result<Vec<ReceivedSource>> {
        ensure!(self.router.is_none(), "Receiving requires a separate non-serving session");
        let ticket = ShareTicket::parse(encoded)?;
        ensure!(ticket.size().is_none_or(|size| size <= MAX_SOURCE_BYTES), "Source file exceeds limit");
        let addresses = ticket.provider_node_addrs().into_iter().map(|addr| {
            let transports = addr.addrs.into_iter().filter(|transport| match transport {
                iroh::TransportAddr::Ip(socket) => !socket.ip().is_unspecified() && !socket.ip().is_multicast() &&
                    (self.internet || match socket.ip() {
                        std::net::IpAddr::V4(ip) => ip.is_private() || ip.is_loopback() || ip.is_link_local(),
                        std::net::IpAddr::V6(ip) => ip.is_loopback() || ip.is_unique_local() || ip.is_unicast_link_local(),
                    }),
                iroh::TransportAddr::Relay(url) => self.internet && url.scheme() == "https" && url.username().is_empty() && url.password().is_none(),
                _ => false,
            });
            iroh::EndpointAddr::from_parts(addr.id, transports)
        }).filter(|addr| !addr.addrs.is_empty()).collect::<Vec<_>>();
        ensure!(!addresses.is_empty(), "No address available for this network mode");
        for addr in &addresses { self.lookup.add_endpoint_info(addr.clone()); }
        let peers = addresses.iter().map(|addr| addr.id).collect::<Vec<_>>();
        let root = ticket.primary().hash();
        let files = if ticket.primary().format() == BlobFormat::Raw {
            vec![(ticket.label().unwrap_or("source.nyastudio").to_owned(), root)]
        } else {
            // Fetch only the small index first. Do not recursively download unknown collections.
            self.download(root, &peers, MAX_METADATA_BYTES, cancel, &|_| {}).await?;
            let bytes = self.store.blobs().get_bytes(root).await?;
            let links = HashSeq::try_from(bytes)?;
            ensure!((2..=MAX_COLLECTION_FILES + 1).contains(&links.len()), "Unsupported collection size");
            let metadata = links.iter().next().context("Missing collection metadata")?;
            self.download(metadata, &peers, MAX_METADATA_BYTES, cancel, &|_| {}).await?;
            let bytes = self.store.blobs().get_bytes(metadata).await?;
            let metadata: CollectionMetadata = postcard::from_bytes(&bytes)?;
            ensure!(&metadata.header == b"CollectionV0." && metadata.names.len() + 1 == links.len(), "Invalid collection metadata");
            metadata.names.into_iter().zip(links.into_iter().skip(1)).collect()
        };
        for (name, _) in &files { validate_name(name)?; }
        tokio::fs::create_dir(output).await.context("Receive directory must be new")?;
        let mut received = Vec::new();
        let mut total = 0;
        for (index, (name, hash)) in files.into_iter().enumerate() {
            let remaining = MAX_SOURCE_BYTES.saturating_sub(total);
            self.download(hash, &peers, remaining, cancel, &|bytes| progress(total + bytes)).await?;
            let bytes = self.complete_size(hash).await?;
            total += bytes;
            ensure!(total <= MAX_SOURCE_BYTES, "Transfer exceeds limit");
            // Sender filenames never control filesystem paths or overwrite existing content.
            let path = output.join(format!("{index}.received"));
            ensure!(!*cancel.borrow(), "Cancelled");
            self.store.blobs().export(hash, &path).await?;
            ensure!(!*cancel.borrow(), "Cancelled");
            received.push(ReceivedSource { name, path, bytes, hash: hash.to_string() });
        }
        Ok(received)
    }

    async fn download(
        &self, hash: Hash, peers: &[iroh::EndpointId], maximum: u64,
        cancel: &mut watch::Receiver<bool>, progress: &(impl Fn(u64) + Send + Sync),
    ) -> Result<()> {
        ensure!(!*cancel.borrow(), "Cancelled");
        for peer in peers {
            let attempt = async {
                let connection = tokio::time::timeout(IDLE_TIMEOUT, self.endpoint.connect(*peer, iroh_blobs::ALPN)).await??;
                let connected = tokio::time::timeout(IDLE_TIMEOUT, fsm::start(connection.clone(), GetRequest::blob(hash), Default::default()).next()).await??;
                let ConnectedNext::StartRoot(root) = tokio::time::timeout(IDLE_TIMEOUT, connected.next()).await?? else { bail!("Missing blob"); };
                let (mut content, size) = tokio::time::timeout(IDLE_TIMEOUT, root.next().next()).await??;
                // Reject the peer's size BEFORE creating an import or allocating an outboard.
                ensure!(size <= maximum, "Transfer exceeds limit");
                let Some(size) = std::num::NonZeroU64::new(size) else {
                    ensure!(hash == Hash::EMPTY, "Invalid empty blob");
                    content.drain().await?;
                    self.store.blobs().add_bytes(Vec::new()).await?;
                    return Ok::<_, anyhow::Error>(());
                };
                // Bounded streaming adaptation of iroh-blobs 0.103.1 api/remote.rs
                // get_blob_ranges_impl (n0-computer, MIT OR Apache-2.0). Each Bao item is verified.
                let imported = self.store.blobs().import_bao(hash, size, 4).await?;
                let write = async {
                    loop {
                        match tokio::time::timeout(IDLE_TIMEOUT, content.next()).await? {
                            BlobContentNext::More((next, item)) => {
                                imported.tx.send(item?).await?;
                                progress(next.stats().payload_bytes_read.min(size.get()));
                                content = next;
                            }
                            BlobContentNext::Done(end) => {
                                drop(imported.tx);
                                let EndBlobNext::Closing(closing) = end.next() else { bail!("Unexpected child blob"); };
                                tokio::time::timeout(IDLE_TIMEOUT, closing.next()).await??;
                                return Ok::<_, anyhow::Error>(());
                            }
                        }
                    }
                };
                let complete = async { imported.rx.await??; Ok::<_, anyhow::Error>(()) };
                tokio::try_join!(write, complete)?;
                connection.close(0u32.into(), b"verified");
                let measured = self.complete_size(hash).await?;
                ensure!(measured <= maximum, "Transfer exceeds limit");
                progress(measured);
                Ok(())
            };
            let result = tokio::select! {
                _ = cancel.changed() => bail!("Cancelled"),
                result = attempt => result,
            };
            if result.is_ok() { return result; }
        }
        bail!("No sender could complete the verified transfer")
    }

    async fn complete_size(&self, hash: Hash) -> Result<u64> {
        match self.store.blobs().status(hash).await? {
            BlobStatus::Complete { size } => Ok(size),
            _ => bail!("Incomplete or unverified content"),
        }
    }

    pub async fn shutdown(self) -> Result<()> {
        if let Ok(mut allowed) = self.allowed.write() { *allowed = None; }
        if let Some(router) = self.router {
            router.shutdown().await?;
        } else {
            self.endpoint.close().await;
        }
        // All provider requests and their read handles are gone. Shut down exactly once.
        self.store.shutdown().await?;
        if let Some(events) = self.events { events.abort(); }
        Ok(())
    }
}

fn validate_name(name: &str) -> Result<()> {
    ensure!(!name.is_empty() && name.len() <= 256 && !name.chars().any(char::is_control), "Invalid source name");
    ensure!(!name.contains('/') && !name.contains('\\') && name != "." && name != "..", "Directories are not supported");
    let extension = name.rsplit('.').next().unwrap_or_default().to_ascii_lowercase();
    ensure!(matches!(extension.as_str(), "nyastudio" | "nyasource" | "rin" | "json" | "apk"), "Unsupported source format");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_names_never_become_paths_or_executables() {
        for name in ["../source.apk", "x\\source.apk", "source.exe", "source.apk\n", "", ".."] {
            assert!(validate_name(name).is_err());
        }
        for name in ["source.apk", "source.nyastudio", "definition.json", "source.nyasource"] {
            assert!(validate_name(name).is_ok());
        }
    }

    #[tokio::test]
    async fn real_quic_transfer_verifies_the_snapshot_without_serving_received_content() -> Result<()> {
        let temp = tempfile::tempdir()?;
        let input = temp.path().join("synthetic.nyastudio");
        let content = br#"{"schema":1,"name":"Synthetic"}"#;
        tokio::fs::write(&input, content).await?;
        let sender = Exchange::open_scoped(temp.path().join("sender"), true, false).await?;
        let receiver = Exchange::open_scoped(temp.path().join("receiver"), false, false).await?;
        let encoded = sender.share(&input, "synthetic.nyastudio").await?;
        let original = ShareTicket::parse(&encoded)?;
        // Explicit loopback routing ensures this test uses no relay or external discovery.
        let port = sender.endpoint.bound_sockets().iter().find(|addr| addr.is_ipv4()).context("No IPv4 socket")?.port();
        let addr = iroh::EndpointAddr::from_parts(sender.endpoint.id(), [iroh::TransportAddr::Ip(([127, 0, 0, 1], port).into())]);
        let ticket = BlobTicket::new(addr, original.primary().hash(), BlobFormat::Raw);
        let encoded = encode_fd2_ticket(&ticket, "synthetic.nyastudio", content.len() as u64)?;
        tokio::fs::write(&input, b"changed after sharing").await?;
        let (_cancel, mut cancelled) = watch::channel(false);
        let outcome = tokio::time::timeout(Duration::from_secs(15), receiver.receive(&encoded, &temp.path().join("received"), &mut cancelled, |_| {})).await;
        receiver.shutdown().await?;
        sender.shutdown().await?;
        let received = outcome??;
        assert_eq!(received.len(), 1);
        assert_eq!(tokio::fs::read(&received[0].path).await?, content);
        assert_eq!(received[0].hash, original.primary().hash().to_string());
        Ok(())
    }

    #[test]
    fn collection_metadata_is_bounded_before_it_becomes_file_paths() {
        #[derive(Serialize)]
        struct Metadata { header: [u8; 13], names: Vec<String> }
        for names in [vec!["../bad.apk".to_owned()], vec!["source.apk".to_owned(); 33], vec!["script.exe".to_owned()]] {
            let encoded = postcard::to_allocvec(&Metadata { header: *b"CollectionV0.", names }).unwrap();
            assert!(postcard::from_bytes::<CollectionMetadata>(&encoded).is_err());
        }
    }

    #[tokio::test]
    async fn a_cancelled_receiver_does_not_open_a_connection_or_create_output() -> Result<()> {
        let temp = tempfile::tempdir()?;
        let receiver = Exchange::open_scoped(temp.path().join("receiver"), false, false).await?;
        let (_sender, mut cancelled) = watch::channel(true);
        let root = Hash::new(b"synthetic source");
        let address = iroh::EndpointAddr::from_parts(iroh::SecretKey::generate().public(), [iroh::TransportAddr::Ip(([127, 0, 0, 1], 1).into())]);
        let ticket = BlobTicket::new(address, root, BlobFormat::Raw);
        let outcome = receiver.receive(&encode_fd2_ticket(&ticket, "source.apk", 16)?, &temp.path().join("incoming"), &mut cancelled, |_| {}).await;
        receiver.shutdown().await?;
        assert!(outcome.is_err());
        assert!(!temp.path().join("incoming/0.received").exists());
        Ok(())
    }

    #[tokio::test]
    async fn sender_shutdown_cancels_a_peer_that_keeps_a_connection_open() -> Result<()> {
        let temp = tempfile::tempdir()?;
        let sender = Exchange::open_scoped(temp.path().join("sender"), true, false).await?;
        let receiver = Exchange::open_scoped(temp.path().join("receiver"), false, false).await?;
        let port = sender.endpoint.bound_sockets().iter().find(|addr| addr.is_ipv4()).context("No IPv4 socket")?.port();
        let address = iroh::EndpointAddr::from_parts(sender.endpoint.id(), [iroh::TransportAddr::Ip(([127, 0, 0, 1], port).into())]);
        let connection = receiver.endpoint.connect(address, iroh_blobs::ALPN).await?;
        let stream = connection.open_bi().await?;
        tokio::time::timeout(Duration::from_secs(3), sender.shutdown()).await??;
        tokio::time::timeout(Duration::from_secs(3), connection.closed()).await?;
        drop(stream);
        receiver.shutdown().await?;
        Ok(())
    }
}
