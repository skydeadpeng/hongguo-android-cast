package io.github.jackotom.hongguo.android;

/** Requested minimum short-edge pixels, not a cosmetic server quality label. */
final class MediaQuality {
    static String normalize(String value) {
        if(value==null) return "auto";
        if(!value.equals("auto")&&!value.equals("720p")&&!value.equals("1080p")&&!value.equals("480p")&&!value.equals("360p")) throw new IllegalArgumentException("播放清晰度无效");
        return value;
    }
    static boolean meets(int width,int height,String quality) {
        quality=normalize(quality);
        return quality.equals("auto") || width>0&&height>0&&Math.min(width,height)>=Integer.parseInt(quality.substring(0,quality.length()-1));
    }
    static String cacheVid(String vid,String quality) {
        quality=normalize(quality);
        // Old auto files have no provenance: they may contain an SEO preview.
        // Rotate only this namespace; explicit-quality App files remain usable.
        return quality.equals("auto")?vid+"|quality=auto|complete=1":vid+"|quality="+quality;
    }
}
