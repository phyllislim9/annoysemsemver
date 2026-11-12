package youtube.data;

import youtube.api.model.Playlist;
import youtube.api.model.SearchResultSnippet.LiveBroadcastContent;
import youtube.api.model.Video;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import static youtube.data.DataSupport.*;

/** STAND-IN data, see DataSupport. Two playlists built from the stand-in videos. */
public class PlaylistData {

    public static List<Playlist> getPlaylistData(List<Video> videos) throws ParseException {
        List<Playlist> p = new ArrayList<Playlist>();
        p.add(new Playlist("PLaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", snippet("UCaaaaaaaaaaaaaaaaaaaaaa", "Alpha Channel", "Alpha favourites", "2020-08-08T00:00:00Z", LiveBroadcastContent.NONE),
                "en", new ArrayList<Video>(videos.subList(0, Math.min(2, videos.size())))));
        p.add(new Playlist("PLbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", snippet("UCbbbbbbbbbbbbbbbbbbbbbb", "Beta Channel", "Beta picks", "2021-09-09T00:00:00Z", LiveBroadcastContent.NONE),
                "es", new ArrayList<Video>(videos.subList(Math.min(2, videos.size()), Math.min(4, videos.size())))));
        return p;
    }
}
