use dzr_core::DeezerClient;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let c = DeezerClient::new();
    let res = c.user_flow(5977912923).await?;
    eprintln!("flow tracks: {} (unique by id)", res.data.len());
    for t in res.data.iter().take(5) {
        eprintln!("  {} - {}", t.artist.name, t.title);
    }
    eprintln!("  ...");
    for t in res.data.iter().rev().take(3) {
        eprintln!("  {} - {}", t.artist.name, t.title);
    }
    Ok(())
}
