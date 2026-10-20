//! Explicit development peer for real Android interoperability checks. Never logs capabilities.
use anyhow::{ensure, Context, Result};
use nyanime_source_exchange::transport::Exchange;
use std::{path::PathBuf, time::Duration};
use tokio::sync::watch;

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() -> Result<()> {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    ensure!(arguments.len() == 5, "Expected: share|receive local|internet|relay-ticket session-dir file|ticket-file ticket-file|output-dir");
    let sharing = match arguments[0].as_str() { "share" => true, "receive" => false, _ => anyhow::bail!("Invalid operation") };
    let internet = match arguments[1].as_str() { "internet" | "relay-ticket" => true, "local" => false, _ => anyhow::bail!("Invalid network mode") };
    let session = PathBuf::from(&arguments[2]);
    tokio::fs::create_dir_all(&session).await?;
    let engine = Exchange::open_scoped(session.join("store"), sharing, internet).await?;
    let result: Result<()> = async {
        if sharing {
            let file = PathBuf::from(&arguments[3]);
            let name = file.file_name().and_then(|value| value.to_str()).context("Invalid filename")?;
            let mut ticket = engine.share(&file, name).await?;
            if arguments[1] == "relay-ticket" {
                use nyanime_source_exchange::ticket::{encode_fd2_ticket, ShareTicket};
                let original = ShareTicket::parse(&ticket)?;
                let address = original.primary().addr();
                let relays = address.addrs.iter().filter(|addr| addr.is_relay()).cloned().collect::<Vec<_>>();
                ensure!(!relays.is_empty(), "Relay did not become ready");
                let address = iroh::EndpointAddr::from_parts(address.id, relays);
                let limited = iroh_blobs::ticket::BlobTicket::new(address, original.primary().hash(), original.primary().format());
                ticket = encode_fd2_ticket(&limited, name, original.size().context("Missing size")?)?;
            }
            tokio::fs::write(&arguments[4], ticket).await?;
            println!("PEER_READY");
            for _ in 0..900 {
                if session.join("stop").exists() { break; }
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        } else {
            let ticket = tokio::fs::read_to_string(&arguments[3]).await?;
            let (_cancel, mut cancelled) = watch::channel(false);
            let files = engine.receive(ticket.trim(), &PathBuf::from(&arguments[4]), &mut cancelled, |_| {}).await?;
            tokio::fs::write(session.join("received.json"), serde_json::to_vec(&files)?).await?;
            println!("PEER_RECEIVED {}", files.len());
        }
        Ok(())
    }.await;
    engine.shutdown().await?;
    result
}
