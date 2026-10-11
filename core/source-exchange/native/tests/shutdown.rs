use anyhow::{ensure, Result};
use iroh::endpoint::presets;
use iroh_blobs::{get::fsm::{self, ConnectedNext}, protocol::GetRequest};
use nyanime_source_exchange::{ticket::ShareTicket, transport::Exchange};
use std::time::Duration;

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn shutdown_does_not_wait_for_a_peer_to_finish_reading_a_blob() -> Result<()> {
    let temporary = tempfile::tempdir()?;
    let file = temporary.path().join("synthetic.apk");
    tokio::fs::write(&file, vec![0x5a; 64 * 1024 * 1024]).await?;
    let sender = Exchange::open_scoped(temporary.path().join("sender"), true, false).await?;
    let code = sender.share(&file, "synthetic.apk").await?;
    let ticket = ShareTicket::parse(&code)?;
    let endpoint = iroh::Endpoint::bind(presets::Minimal).await?;
    let connection = endpoint.connect(ticket.primary().addr().clone(), iroh_blobs::ALPN).await?;
    let connected = fsm::start(connection.clone(), GetRequest::blob(ticket.primary().hash()), Default::default()).next().await?;
    let ConnectedNext::StartRoot(root) = connected.next().await? else { anyhow::bail!("Missing root"); };
    let (content, size) = root.next().next().await?;
    ensure!(size == 64 * 1024 * 1024, "Wrong source size");
    // Keep the receive stream alive without draining it. The sender must revoke
    // the connection instead of waiting for the peer before closing its store.
    tokio::time::sleep(Duration::from_millis(100)).await;
    let outcome = tokio::time::timeout(Duration::from_secs(3), sender.shutdown()).await;
    drop(content);
    endpoint.close().await;
    outcome??;
    Ok(())
}
