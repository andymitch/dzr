package main

import (
	"flag"
	"fmt"
	"os"

	tea "charm.land/bubbletea/v2"
	"github.com/andymitch/dzr/tui/internal/deezer"
)

func main() {
	quality := flag.String("quality", "flac", "preferred audio quality: flac, 320 or 128")
	logout := flag.Bool("logout", false, "forget the saved Deezer session and exit")
	flag.Parse()
	if *logout {
		deezer.ClearSession()
		return
	}

	session, err := deezer.LoadSession()
	client := deezer.NewClient(session)
	var prog *tea.Program
	player, perr := NewPlayer(client, deezer.Quality(*quality), func() { go prog.Send(endedMsg{}) })
	if perr != nil {
		fmt.Fprintln(os.Stderr, "audio:", perr)
		os.Exit(1)
	}
	prog = tea.NewProgram(newModel(client, player, err == nil))
	if _, err := prog.Run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
