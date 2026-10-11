pub type Result<T> = std::result::Result<T, LightningP2PError>;

#[derive(Debug, thiserror::Error)]
pub enum LightningP2PError {
    #[error("Invalid transfer ticket: {0}")]
    InvalidTicket(String),
    #[error("Invalid transfer metadata")]
    Json(#[from] serde_json::Error),
}

