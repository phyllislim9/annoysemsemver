package youtube.data;

import youtube.api.model.Channel;
import youtube.api.model.SearchResultSnippet.LiveBroadcastContent;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static youtube.data.DataSupport.*;

/** STAND-IN data, see DataSupport. Three channels; their ids match the channel ids used by the stand-in videos. */
public class ChannelData {

    public static List<Channel> getChannelData() throws ParseException {
        List<Channel> c = new ArrayList<Channel>();
        c.add(new Channel("UCaaaaaaaaaaaaaaaaaaaaaa", snippet("UCaaaaaaaaaaaaaaaaaaaaaa", "Alpha Channel", "Alpha Channel", "2015-02-01T00:00:00Z", LiveBroadcastContent.NONE),
                "show", "en", Arrays.asList("/m/02wbm"), 51000L, 2L, "owner-alpha"));
        c.add(new Channel("UCbbbbbbbbbbbbbbbbbbbbbb", snippet("UCbbbbbbbbbbbbbbbbbbbbbb", "Beta Channel", "Beta Channel", "2016-05-05T00:00:00Z", LiveBroadcastContent.NONE),
                "other", "es", null, 900250L, 2L, "owner-beta"));
        c.add(new Channel("UCxwvutsrqponmlkjihgfedc", snippet("UCxwvutsrqponmlkjihgfedc", "Mine Channel", "Mine Channel", "2017-07-07T00:00:00Z", LiveBroadcastContent.NONE),
                "other", "en", Arrays.asList("/m/07bxq"), 3320L, 2L, "owner-mine"));
        return c;
    }
}
