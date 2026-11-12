package youtube.data;

import youtube.api.model.SearchResultSnippet.LiveBroadcastContent;
import youtube.api.model.Video;

import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static youtube.data.DataSupport.*;

/** STAND-IN data, see DataSupport. Six videos on three channels, varied on every property the search filters look at. */
public class VideoData {

    public static List<Video> getVideoData() throws ParseException {
        List<Video> v = new ArrayList<Video>();
        // id, snippet, type, category, language, duration, dimension, definition, caption, regionAllowed, regionBlocked,
        // embeddable, license, topics, views, likes, dislikes, ytRating, liveStreaming, liveViewers, forDeveloper
        v.add(new Video("vid00000001", snippet("UCaaaaaaaaaaaaaaaaaaaaaa", "Alpha Channel", "Cooking pasta at home", "2020-03-01T10:00:00Z", LiveBroadcastContent.NONE),
                "other", category("26", "Howto"), "en", Duration.ofSeconds(300), Video.VideoDimension.TWO_D, Video.VideoDefinition.HD, true,
                null, null, true, Video.VideoLicense.YOUTUBE, Arrays.asList("/m/02wbm"), 1000L, 90L, 10L, null, false, null, false));
        v.add(new Video("vid00000002", snippet("UCaaaaaaaaaaaaaaaaaaaaaa", "Alpha Channel", "Live concert tonight", "2021-06-15T20:00:00Z", LiveBroadcastContent.LIVE),
                "other", category("10", "Music"), "en", Duration.ofSeconds(5400), Video.VideoDimension.TWO_D, Video.VideoDefinition.HD, false,
                null, null, true, Video.VideoLicense.YOUTUBE, Arrays.asList("/m/04rlf"), 50000L, 4000L, 200L, null, true, 1200L, false));
        v.add(new Video("vid00000003", snippet("UCbbbbbbbbbbbbbbbbbbbbbb", "Beta Channel", "Pasta carbonara recipe", "2019-11-20T08:30:00Z", LiveBroadcastContent.NONE),
                "episode", category("26", "Howto"), "es", Duration.ofSeconds(180), Video.VideoDimension.THREE_D, Video.VideoDefinition.SD, true,
                Arrays.asList("es", "us"), null, false, Video.VideoLicense.CREATIVE_COMMON, Arrays.asList("/m/02wbm", "/m/01k8wb"), 250L, 20L, 5L, null, false, null, true));
        v.add(new Video("vid00000004", snippet("UCbbbbbbbbbbbbbbbbbbbbbb", "Beta Channel", "Movie trailer premiere", "2022-01-10T12:00:00Z", LiveBroadcastContent.UPCOMING),
                "movie", category("1", "Film"), "en", Duration.ofSeconds(2400), Video.VideoDimension.TWO_D, Video.VideoDefinition.HD, false,
                null, Arrays.asList("US"), true, Video.VideoLicense.YOUTUBE, Arrays.asList("/m/02vxn"), 900000L, 70000L, 3000L, "ytAgeRestricted", true, 0L, false));
        v.add(new Video("vid00000005", snippet("UCxwvutsrqponmlkjihgfedc", "Mine Channel", "My travel vlog", "2023-04-02T15:45:00Z", LiveBroadcastContent.NONE),
                "other", category("19", "Travel"), "en", Duration.ofSeconds(900), Video.VideoDimension.TWO_D, Video.VideoDefinition.SD, true,
                null, null, true, Video.VideoLicense.CREATIVE_COMMON, Arrays.asList("/m/07bxq"), 3200L, 300L, 15L, null, false, null, false));
        v.add(new Video("vid00000006", snippet("UCxwvutsrqponmlkjihgfedc", "Mine Channel", "Short tips for developers", "2018-09-09T09:09:09Z", LiveBroadcastContent.NONE),
                "other", category("28", "Science"), "fr", Duration.ofSeconds(90), Video.VideoDimension.TWO_D, Video.VideoDefinition.HD, false,
                null, null, true, Video.VideoLicense.YOUTUBE, null, 120L, 10L, 1L, null, false, null, true));
        return v;
    }
}
