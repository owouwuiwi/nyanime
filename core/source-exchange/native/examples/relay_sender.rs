//! Diagnostic fixture: no IP transport, so a successful transfer necessarily uses a relay.
use anyhow::{ensure, Result};
use iroh::{endpoint::presets, protocol::Router, Endpoint};
use iroh_blobs::{store::fs::FsStore, ticket::BlobTicket, BlobFormat, BlobsProtocol};
use nyanime_source_exchange::ticket::encode_fd2_ticket;
use std::{path::PathBuf, time::Duration};

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() -> Result<()> {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    ensure!(arguments.len() == 3, "Expected session-dir synthetic-file ticket-file");
    let session = PathBuf::from(&arguments[0]);
    let input = PathBuf::from(&arguments[1]);
    ensure!(input.metadata()?.len() < 65536, "Use only a small synthetic fixture");
    let store = FsStore::load(session.join("store")).await?;
    let tagged = store.blobs().add_path(&input).await?;
    let endpoint = Endpoint::builder(presets::N0).clear_ip_transports().bind().await?;
    ensure!(endpoint.bound_sockets().is_empty(), "Probe must have no IP transport");
    let router = Router::builder(endpoint.clone()).accept(iroh_blobs::ALPN, BlobsProtocol::new(&store, None)).spawn();
    tokio::time::timeout(Duration::from_secs(12), endpoint.online()).await?;
    let ticket = BlobTicket::new(endpoint.addr(), tagged.hash, BlobFormat::Raw);
    tokio::fs::write(&arguments[2], encode_fd2_ticket(&ticket, "synthetic.nyastudio", input.metadata()?.len())?).await?;
    println!("RELAY_ONLY_SENDER_READY; no IP sockets");
    for _ in 0..300 {
        if session.join("stop").exists() { break; }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    router.shutdown().await?;
    Ok(())
}
