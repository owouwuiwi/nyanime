use anyhow::Result;
use iroh::{endpoint::{presets, QuicTransportConfig}, Endpoint};
use std::time::{Duration, Instant};

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() -> Result<()> {
    for bounded in [false, true] {
        let builder = Endpoint::builder(presets::N0);
        let builder = if bounded {
            builder.transport_config(QuicTransportConfig::builder()
                .max_concurrent_bidi_streams(4u32.into()).max_concurrent_uni_streams(0u32.into())
                .max_idle_timeout(Some(Duration::from_secs(25).try_into()?)).build())
        } else { builder };
        let endpoint = builder.bind().await?;
        let started = Instant::now();
        let connected = tokio::time::timeout(Duration::from_secs(12), endpoint.online()).await.is_ok();
        println!("RELAY_PROBE bounded={bounded} connected={connected} elapsed_ms={}", started.elapsed().as_millis());
        endpoint.close().await;
    }
    Ok(())
}
