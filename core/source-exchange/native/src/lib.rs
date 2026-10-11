//! On-demand, headless Lightning-compatible transport. No source rules or Android UI live here.

pub mod error;
pub mod ticket;
pub mod transport;
#[cfg(target_os = "android")]
mod android;

