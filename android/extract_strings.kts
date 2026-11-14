import java.io.File

val missing = listOf(
    "common_back", "common_cancel", "common_delete", "common_play", "common_playlist", "common_shuffle",
    "library_toast_added_to_queue", "library_toast_playing_next",
    "playlist_action_add_songs", "playlist_action_delete_playlist", "playlist_action_edit_playlist",
    "playlist_action_export_playlist", "playlist_action_play_it", "playlist_action_remove_songs",
    "playlist_action_reorder_songs", "playlist_action_set_default_transition",
    "playlist_cd_add_songs", "playlist_cd_remove_songs", "playlist_cd_reorder_songs",
    "playlist_dialog_delete_body", "playlist_dialog_delete_title",
    "playlist_empty_add_hint", "playlist_empty_folder_label", "playlist_empty_title",
    "playlist_more_options_title", "playlist_not_found", "playlist_options_title",
    "playlist_song_duration_line", "playlist_sort_songs_title"
)

val resDir = File("/tmp/PixelPlayer/app/src/main/res/values/")
val outXml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")

val found = mutableSetOf<String>()

resDir.listFiles { f -> f.name.endsWith(".xml") }?.forEach { file ->
    val lines = file.readLines()
    for (line in lines) {
        val match = """<string name="([^"]+)">(.+)</string>""".toRegex().find(line)
        if (match != null) {
            val name = match.groupValues[1]
            if (name in missing && !found.contains(name)) {
                outXml.append("    ${line.trim()}\n")
                found.add(name)
            }
        }
    }
}

outXml.append("</resources>")
File("app/src/main/res/values/strings_playlist_fix.xml").writeText(outXml.toString())
println("Extracted ${found.size} strings")
