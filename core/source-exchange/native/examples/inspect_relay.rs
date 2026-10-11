use anyhow::Result;
use nyanime_source_exchange::ticket::ShareTicket;
fn main() -> Result<()> {
    let file = std::env::args().nth(1).expect("ticket file");
    let ticket = ShareTicket::parse(std::fs::read_to_string(file)?.trim())?;
    for address in &ticket.primary().addr().addrs {
        if let iroh::TransportAddr::Relay(url) = address { println!("{url}"); }
    }
    Ok(())
}
