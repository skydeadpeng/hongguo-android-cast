package io.github.jackotom.hongguo.android;
import org.json.*;
/** Actual LibraryStore constructor captures its immutable account writer, not global selection. */
public final class AccountLibraryIsolationTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static final class Access implements LibraryStore.HistoryAccess {
        final HistorySyncState state;final String writer;Access(HistorySyncState s,String w){state=s;writer=w;}
        public JSONArray rows()throws JSONException{return state.rows();}public void record(JSONObject row)throws JSONException{state.record(writer,row);}
    }
    public static void main(String[]args)throws Exception {
        SearchHistoryStoreTest.AppContext context=new SearchHistoryStoreTest.AppContext();LibraryStore.setHistoryFactory(null);
        LibraryStore guest=new LibraryStore(context);JSONObject s=LibraryStore.series(HistorySyncStateTest.row("123456",2,100));JSONObject e=LibraryStore.episode(HistorySyncStateTest.row("123456",2,100));guest.history(s,e,100);
        HistorySyncState accountA=new HistorySyncState("{}"),accountB=new HistorySyncState("{}");LibraryStore.setHistoryFactory(()->new Access(accountA,"player-a"));LibraryStore a=new LibraryStore(context);
        check(a.library().getJSONArray("history").length()==0,"login never autoimports guest data");guest.history(s,e,200);check(a.library().getJSONArray("history").length()==0,"old guest player never writes into logged in account");
        a.history(s,e,300);LibraryStore.setHistoryFactory(()->new Access(accountB,"player-b"));LibraryStore b=new LibraryStore(context);a.history(s,e,400);check(b.library().getJSONArray("history").length()==0&&a.position(s,e)==400,"old player remains original account after switch");
        b.history(s,e,500);LibraryStore.setHistoryFactory(null);LibraryStore restoredGuest=new LibraryStore(context);check(restoredGuest.position(s,e)==200,"logout restores guest history untouched");check(b.position(s,e)==500,"accountB isolated");
        a.toggle(s);check(b.library().getJSONArray("favorites").length()==1&&restoredGuest.library().getJSONArray("favorites").length()==1,"favorites remain existing device behavior");
        a.addSearchHistory(new JSONObject().put("keyword","old search"));check(restoredGuest.searchHistory().getJSONArray("keywords").getString(0).equals("old search"),"search remains local/shared unchanged");
        a.addDownload(9,s,e);check(restoredGuest.downloads().length()==1,"downloads/cache owner records remain device local");
        check(restoredGuest.library().getJSONArray("history").getJSONObject(0).getLong("positionMs")==200,"account write never changes guest history file");
        System.out.println("ACCOUNT_LIBRARY_ISOLATION_OK checks="+checks+" guestPreserved=true capturedWriter=true playbackAlgorithms=untouched");
    }
}
