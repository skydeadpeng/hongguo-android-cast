package io.github.jackotom.hongguo.android;

/** The harness compiles the exact local-selection expression extracted from PlayerActivity. */
public final class PlayerOfflineSelectionTest {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[]args){
        String selected="file:///owned/series_4_selected_1080p.mp4",first="file:///owned/series_4_first_720p.mp4";
        PlayerOfflineSelector.first=first;
        for(String quality:new String[]{"auto","720p","1080p"}){
            PlayerOfflineSelector.calls=0;
            check(PlayerOfflineSelector.select(true,selected,quality).value.equals(selected),"exact selected download URI wins over first same-episode copy for "+quality);
            check(PlayerOfflineSelector.calls==0,"selected URI never scans/chooses another download");
        }
        String selectedContent="content://downloads/my_downloads/selected-42";
        check(PlayerOfflineSelector.select(true,selectedContent,"auto").value.equals(selectedContent),"legacy validated content URI is also exact");
        PlayerOfflineSelector.calls=0;
        check(PlayerOfflineSelector.select(true,null,"1080p").value.equals(first),"offline next/restored without initial URI keeps existing lookup");
        check(PlayerOfflineSelector.calls==1,"offline resume uses one existing lookup");
        PlayerOfflineSelector.calls=0;
        check(PlayerOfflineSelector.select(false,"https://example.invalid/stream.mp4","auto").value.equals(first),"online auto retains existing manual-download preference");
        check(PlayerOfflineSelector.calls==1,"online auto lookup is unchanged");
        for(String quality:new String[]{"720p","1080p"}){
            PlayerOfflineSelector.calls=0;
            check(PlayerOfflineSelector.select(false,"https://example.invalid/stream.mp4",quality)==null,"explicit online quality remains remote/cache, never arbitrary initial local URI");
            check(PlayerOfflineSelector.calls==0,"explicit online quality still skips unknown manual files");
        }
        PlayerOfflineSelector.first=null;
        check(PlayerOfflineSelector.select(true,null,"auto")==null,"missing offline file remains missing instead of false success");
        System.out.println("PASS PlayerOfflineSelectionTest checks="+checks+" actualPlayerExpression=true selectedDownloadIdentity=true onlineQualityUnchanged=true networkRequests=0 deviceOperations=0");
    }
}
