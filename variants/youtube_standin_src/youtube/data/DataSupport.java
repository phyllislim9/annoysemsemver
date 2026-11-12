package youtube.data;

import youtube.api.model.SearchResultSnippet;
import youtube.api.model.VideoCategory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * STAND-IN sample data for the YouTube benchmark. The original youtube.data package (VideoData, ChannelData,
 * PlaylistData) is not part of this copy of the benchmark, so the checker overlays these three small classes when it
 * builds a temporary copy of the project (--extra-src). The original sources are not modified. Results obtained with
 * this data are results on invented sample data, not on the benchmark's own data.
 */
final class DataSupport {
    private DataSupport() { }

    static Date date(String iso) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(iso);
    }

    static SearchResultSnippet snippet(String channelId, String channelTitle, String title, String publishedAt, SearchResultSnippet.LiveBroadcastContent live) throws ParseException {
        SearchResultSnippet s = new SearchResultSnippet();
        s.setChannelId(channelId);
        s.setChannelTitle(channelTitle);
        s.setTitle(title);
        s.setDescription("Description of " + title);
        s.setPublishedAt(date(publishedAt));
        s.setLiveBroadcastContent(live);
        return s;
    }

    /** VideoCategory is built through whatever constructor / setters it offers (its source is not needed here) */
    static VideoCategory category(String id, String title) {
        try {
            for (Constructor<?> k : VideoCategory.class.getDeclaredConstructors()) {
                Class<?>[] pt = k.getParameterTypes();
                boolean strings = true;
                for (Class<?> p : pt) strings &= p == String.class;
                if (!strings) continue;
                Object[] args = new Object[pt.length];
                for (int i = 0; i < pt.length; i++) args[i] = i == 0 ? id : title;
                k.setAccessible(true);
                return (VideoCategory) k.newInstance(args);
            }
            Constructor<?> k = VideoCategory.class.getDeclaredConstructor();
            k.setAccessible(true);
            VideoCategory c = (VideoCategory) k.newInstance();
            for (Method m : VideoCategory.class.getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    if (m.getName().equals("setId")) m.invoke(c, id);
                    if (m.getName().equals("setTitle") || m.getName().equals("setName")) m.invoke(c, title);
                }
            }
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build a VideoCategory for the stand-in data", e);
        }
    }
}
