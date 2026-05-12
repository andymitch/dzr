use dzr_core::DeezerClient;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let c = DeezerClient::new();
    eprintln!("user_albums...");
    let res = c.user_albums(5977912923).await;
    match res {
        Ok(p) => {
            eprintln!("got {} albums", p.data.len());
            for a in &p.data[..p.data.len().min(3)] {
                eprintln!("  {} - {}", a.title, a.artist.name);
            }
        }
        Err(e) => eprintln!("ERR: {e}"),
    }
    Ok(())
}
