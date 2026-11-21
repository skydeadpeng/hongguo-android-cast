package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import org.json.*;

/** Native TV window over the same source service used by the WebView flavors. */
public final class TvSourceActivity extends Activity {
    private SourceService service;
    private JSONObject source, detail;
    private JSONArray sources = new JSONArray(), categories = new JSONArray(), catalog = new JSONArray(), entries = new JSONArray();
    private LinearLayout actions;
    private ListView list;
    private TextView heading, status;
    private String view = "sources", category = "", keyword = "";
    private int page = 1, revision;
    private boolean more, busy, closed;
    private String variantEpisode = "";
    private JSONArray recentVariants;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.prepareWindow(this);
        service = new SourceService(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BACKGROUND);
        int edge = TvUi.edgeInset(this), vertical = getResources().getConfiguration().screenHeightDp < 450 ? 12 : 20;
        root.setPadding(dp(edge), dp(vertical), dp(edge), dp(vertical));
        heading = TvUi.heading(this, "影视来源", 28);
        heading.setMaxLines(2); heading.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        actions = TvUi.row(this);
        HorizontalScrollView actionStrip = new HorizontalScrollView(this);
        actionStrip.setHorizontalScrollBarEnabled(false);
        actionStrip.setFocusable(false); // Buttons, not the scroller, own D-pad focus.
        actionStrip.addView(actions, new HorizontalScrollView.LayoutParams(-2, -2));
        root.addView(actionStrip, new LinearLayout.LayoutParams(-1, -2));
        status = TvUi.text(this, "", 15, TvUi.MUTED);
        status.setMaxLines(3); status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2);
        statusLayout.bottomMargin = dp(8);
        root.addView(status, statusLayout);
        list = new ListView(this);
        list.setBackground(TvUi.panelShape(this));
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        list.setDivider(new ColorDrawable(TvUi.BORDER)); list.setDividerHeight(dp(1));
        list.setSelector(TvUi.selector(this));
        if (Build.VERSION.SDK_INT >= 26) list.setDefaultFocusHighlightEnabled(false);
        list.setDrawSelectorOnTop(true); list.setFocusable(true);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setOnItemClickListener((parent, item, at, id) -> select(entries.optJSONObject(at)));
        setContentView(root);
        showSources();
    }

    private int dp(int value) { return TvUi.dp(this, value); }
    private interface Done { void accept(Object result, String session) throws Exception; }

    private JSONObject request(String operation) {
        JSONObject params = new JSONObject();
        put(params, "operation", operation);
        if (source != null) put(params, "sourceId", source.optString("id"));
        return params;
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key,value); } catch (JSONException error) { throw new IllegalArgumentException("来源请求参数无效"); }
    }

    private void call(JSONObject params, String waiting, Done done) {
        final int ticket = revision;
        if (closed || busy) return;
        busy = true;
        if (!waiting.isEmpty()) status.setText(waiting);
        service.submit(params, new SourceService.Reply() {
            @Override public void success(Object result, String session) {
                if (closed || ticket != revision) { if (session != null) SourcePlaybackSessions.close(session); return; }
                busy = false;
                try { done.accept(result, session); }
                catch (Exception error) { status.setText("来源返回的数据格式不受支持"); }
            }
            @Override public void failure(String code, String message) {
                if (closed || ticket != revision) return;
                busy = false;
                status.setText(message == null || message.isEmpty() ? "来源请求未完成" : message);
            }
        });
    }

    private void resetRequest() {
        revision++; busy = false;
        if (source == null) return;
        service.submit(request("cancel"), new SourceService.Reply() {
            @Override public void success(Object result, String session) { }
            @Override public void failure(String code, String message) { }
        });
    }

    private void button(String label, Runnable action) {
        Button button = TvUi.button(this, label);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(44));
        layout.rightMargin = dp(8); layout.topMargin = dp(8); layout.bottomMargin = dp(8);
        actions.addView(button, layout);
        button.setOnClickListener(v -> action.run());
    }

    private void rows(JSONArray values, String kind) {
        entries = values;
        ArrayList<CharSequence> labels = new ArrayList<>();
        for (int i=0;i<values.length();i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) { labels.add("—"); continue; }
            String name, metadata = "";
            if ("sources".equals(kind)) {
                name = item.optString("name");
                metadata = (item.optBoolean("adult") ? " · 限制级" : "") + (item.optBoolean("enabled") ? "" : " · 未启用");
            } else {
                name = item.optString("title");
                if ("episodes".equals(kind) && !item.optBoolean("playable")) metadata = " · 暂不可播放";
                else if ("catalog".equals(kind) && item.optInt("episodeCount") > 1) metadata = " · " + item.optInt("episodeCount") + " 集";
            }
            SpannableString label = new SpannableString(name + metadata);
            if (!metadata.isEmpty()) label.setSpan(new ForegroundColorSpan(TvUi.MUTED), name.length(), label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            labels.add(label);
        }
        list.setAdapter(new ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView)super.getView(position, convertView, parent);
                row.setTextColor(TvUi.TEXT); row.setTextSize(17); row.setMinHeight(dp(44));
                row.setPadding(dp(16), dp(6), dp(16), dp(6));
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.setMaxLines(2); row.setEllipsize(TextUtils.TruncateAt.END);
                return row;
            }
        });
        if (!labels.isEmpty()) list.requestFocus();
    }

    private void showSources() {
        resetRequest(); source=null; detail=null; view="sources";
        heading.setText("影视来源");
        actions.removeAllViews(); button("返回首页", this::finish); button("刷新来源", this::showSources);
        rows(sources,view);
        call(request("list"), "正在读取来源…", (value, session) -> {
            sources=(JSONArray)value;
            status.setText("选择来源开始浏览。限制级来源默认关闭，单独启用。");
            rows(sources,view);
        });
    }

    private void select(JSONObject item) {
        if (item == null || busy) return;
        if ("sources".equals(view)) {
            if ("hongguo".equals(item.optString("id"))) { finish(); return; }
            if (!item.optBoolean("enabled")) {
                new AlertDialog.Builder(this).setTitle("启用 " + item.optString("name"))
                    .setMessage(item.optBoolean("adult") ? "这是限制级来源。只在主动确认后启用。" : "该来源当前已关闭。")
                    .setNegativeButton("取消", null).setPositiveButton("启用", (dialog, which) -> toggle(item,true)).show();
            } else open(item);
        } else if ("catalog".equals(view)) openDetail(item.optString("seriesId"));
        else if ("episodes".equals(view) && item.optBoolean("playable")) selectEpisode(item);
    }

    private void toggle(JSONObject item, boolean enable) {
        JSONObject params=request("settings"); put(params,"sourceId",item.optString("id")); put(params,"enabled",enable);
        call(params, enable ? "正在启用…" : "正在停用…", (value, session) -> {
            sources=(JSONArray)value;
            if (enable) {
                for (int i=0;i<sources.length();i++) {
                    JSONObject row=sources.optJSONObject(i);
                    if (row!=null && item.optString("id").equals(row.optString("id"))) { open(row); return; }
                }
            }
            showSources();
        });
    }

    private void open(JSONObject item) {
        resetRequest(); source=item; detail=null; category=""; keyword=""; page=1;
        catalog=new JSONArray(); categories=new JSONArray(); more=false;
        variantEpisode=""; recentVariants=null;
        renderCatalog();
        call(request("categories"),"正在读取分类…",(value, session) -> {
            categories=(JSONArray)value;
            JSONObject first=categories.optJSONObject(0);
            category=first==null?"":first.optString("id");
            load(false);
        });
    }
    private boolean hasCapability(String name) {
        if (source == null) return false;
        JSONArray caps=source.optJSONArray("capabilities");
        if(caps!=null)for(int i=0;i<caps.length();i++) if(name.equals(caps.optString(i))) return true;
        return false;
    }
    private void renderCatalog() {
        view="catalog"; heading.setText(source.optString("name") + " · 影视来源");
        actions.removeAllViews();
        button("换来源",this::showSources);
        if(categories.length()>0) button("分类",this::chooseCategory);
        if(hasCapability("search")) button("搜索",this::showSearch);
        if(hasCapability("account")) button("账号",this::showAccount);
        button("刷新", () -> { if (categories.length()==0 && keyword.isEmpty()) open(source); else load(false); });
        if(more) button("下一页",() -> load(true));
        button("停用来源",() -> new AlertDialog.Builder(this).setTitle("停用 " + source.optString("name") + "？")
            .setNegativeButton("取消",null).setPositiveButton("停用",(dialog, which)->toggle(source,false)).show());
        rows(catalog,view);
    }
    private void chooseCategory() {
        if (busy || categories.length()==0) return;
        String[] labels=new String[categories.length()];
        for(int i=0;i<labels.length;i++){ JSONObject row=categories.optJSONObject(i); labels[i]=row==null?"—":row.optString("label"); }
        new AlertDialog.Builder(this).setTitle("选择分类").setItems(labels,(dialog, which)->{
            JSONObject row=categories.optJSONObject(which);
            if(row!=null){category=row.optString("id");keyword="";load(false);}
        }).setNegativeButton("取消",null).show();
    }
    private void showSearch() {
        if(busy)return;
        EditText field=field("片名",false); field.setText(keyword);
        new AlertDialog.Builder(this).setTitle("搜索当前来源").setView(field).setNegativeButton("取消",null)
            .setPositiveButton("搜索",(dialog, which)->{keyword=field.getText().toString().trim();load(false);}).show();
    }
    private void load(boolean append) {
        if(busy || append&&!more)return;
        int next=append?page+1:1;
        JSONObject params=request(keyword.isEmpty()?"catalog":"search");
        put(params,"page",next); put(params,keyword.isEmpty()?"category":"keyword",keyword.isEmpty()?category:keyword);
        call(params,"正在读取片单…",(value, session)->{
            JSONObject data=(JSONObject)value;
            JSONArray fetched=data.getJSONArray("items");
            if(!append)catalog=new JSONArray();
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(int i=0;i<catalog.length();i++)seen.add(catalog.getJSONObject(i).getString("seriesId"));
            for(int i=0;i<fetched.length() && catalog.length()<2048;i++){
                JSONObject item=fetched.getJSONObject(i);
                if(seen.add(item.getString("seriesId")))catalog.put(item);
            }
            more=data.optBoolean("hasMore")&&catalog.length()<2048; page=next;
            renderCatalog();
            status.setText(catalog.length()==0?"没有找到影片":(keyword.isEmpty()?"已加载 ":"搜索 "+keyword+" · ")+catalog.length()+" 项");
        });
    }
    private void openDetail(String seriesId) {
        JSONObject params=request("detail");put(params,"seriesId",seriesId);
        call(params,"正在读取分集…",(value, session)->{
            detail=(JSONObject)value; view="episodes";
            heading.setText(detail.optString("title"));
            actions.removeAllViews(); button("返回片单",this::backToCatalog);
            if(hasCapability("account"))button("来源账号",this::showAccount);
            button("刷新分集",()->openDetail(seriesId));
            JSONArray list=detail.getJSONArray("episodes");
            status.setText(list.length()==0?"来源未返回分集":list.length()+" 集");
            rows(list,view);
        });
    }
    private void selectEpisode(JSONObject episode){
        JSONArray options=episode.optJSONArray("lineOptions");
        if((options==null || options.length()==0) && episode.optString("episodeId").equals(variantEpisode))
            options=recentVariants;
        if(options==null || options.length()<2) { play(episode,""); return; }
        final JSONArray choices=options;
        String[] names=new String[choices.length()+1];names[0]="默认线路";
        for(int i=0;i<choices.length();i++)names[i+1]=choices.optJSONObject(i).optString("label");
        new AlertDialog.Builder(this).setTitle("播放线路").setItems(names,(d,w)->play(episode,w==0?"":choices.optJSONObject(w-1).optString("id")))
            .setNegativeButton("取消",null).show();
    }
    private void play(JSONObject episode,String variant) {
        JSONObject params=request("play");
        put(params,"seriesId",detail.optString("seriesId"));put(params,"episodeId",episode.optString("episodeId"));
        if(!variant.isEmpty())put(params,"variant",variant);
        call(params,"正在准备播放…",(value, session)->{
            variantEpisode=episode.optString("episodeId");
            recentVariants=value instanceof JSONObject?((JSONObject)value).optJSONArray("variants"):null;
            if(session==null || !SourcePlaybackSessions.isPending(session)){status.setText("播放请求已取消");return;}
            status.setText("请选择分集");
            // The player reuses this activity's service lane for in-player episode switching;
            // the lane holds the issued detail cache, so play requests stay bounded to it.
            SourcePlaybackActivity.share(service);
            TvPlay.launch(this,new Intent(this,SourcePlaybackActivity.class)
                .putExtra(SourcePlaybackSessions.EXTRA_SESSION,session)
                .putExtra("sourceId",source.optString("id"))
                .putExtra("seriesId",detail.optString("seriesId"))
                .putExtra("currentIndex",episodeIndex(episode)));
        });
    }
    private int episodeIndex(JSONObject episode){
        JSONArray list=detail.optJSONArray("episodes");
        String id=episode.optString("episodeId");
        for(int i=0;list!=null&&i<list.length();i++)
            if(list.optJSONObject(i)!=null&&id.equals(list.optJSONObject(i).optString("episodeId")))return i;
        return -1;
    }
    private void backToCatalog() { resetRequest();detail=null;renderCatalog();status.setText(""); }

    private EditText field(String hint,boolean password) {
        EditText edit=new EditText(this); edit.setHint(hint); edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:0));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        if(android.os.Build.VERSION.SDK_INT>=26)edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return edit;
    }
    private JSONObject account(String command){JSONObject p=request("account");put(p,"command",command);return p;}
    private void showAccount() {
        if(!hasCapability("account"))return;
        call(account(""),"正在读取来源账号…",(value, session)->{
            JSONObject item=(JSONObject)value; status.setText("");
            if(item.optBoolean("loggedIn"))new AlertDialog.Builder(this).setTitle("来源账号")
                .setMessage("已登录："+item.optString("name")).setNegativeButton("返回",null).setPositiveButton("退出登录",(d,w)->
                    call(account("logout"),"正在退出…",(out,s)->status.setText("已退出。播放前请刷新分集。"))).show();
            else loginDialog();
        });
    }
    private void loginDialog() {
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),0,dp(16),0);
        EditText username=field("用户名或邮箱",false), password=field("密码（不保存）",true);
        form.addView(username);form.addView(password);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("登录当前来源").setView(form).setNegativeButton("取消",null)
            .setPositiveButton("获取验证",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String id=username.getText().toString().trim(), secret=password.getText().toString();
            if(id.isEmpty()||secret.isEmpty()){username.setError("请填写账号和密码");return;}
            password.setText("");dialog.dismiss();
            call(account("captcha"),"正在等待滑块验证…",(result, s)->showCaptcha((JSONObject)result,id,secret));
        }));
        dialog.show();
    }
    private void showCaptcha(JSONObject data,String user,String password) throws Exception {
        Bitmap bg=image(data.getString("backgroundImage")), piece=image(data.getString("templateImage"));
        int width=data.getInt("backgroundWidth"), height=data.getInt("backgroundHeight");
        int pw=data.getInt("templateWidth"), ph=data.getInt("templateHeight");
        if(width<1||height<1||pw<1||ph<1||pw>=width||ph>height||width>4000||height>4000){status.setText("验证图片尺寸无效");return;}
        int drawWidth=Math.min(dp(320),getResources().getDisplayMetrics().widthPixels/2), drawHeight=Math.max(1,drawWidth*height/width);
        if(drawHeight>dp(260)){ drawHeight=dp(260);drawWidth=Math.max(1,drawHeight*width/height); }
        final int displayWidth=drawWidth;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),0,dp(16),0);
        FrameLayout image=new FrameLayout(this); form.addView(image,new LinearLayout.LayoutParams(drawWidth,drawHeight));
        ImageView background=new ImageView(this);background.setScaleType(ImageView.ScaleType.FIT_XY);background.setImageBitmap(bg);
        image.addView(background,new FrameLayout.LayoutParams(-1,-1));
        ImageView sliderPiece=new ImageView(this);sliderPiece.setScaleType(ImageView.ScaleType.FIT_XY);sliderPiece.setImageBitmap(piece);
        image.addView(sliderPiece,new FrameLayout.LayoutParams(Math.max(1,drawWidth*pw/width),Math.max(1,drawHeight*ph/height)));
        SeekBar slider=new SeekBar(this);slider.setMax(width-pw);slider.setKeyProgressIncrement(Math.max(1,width/100));
        slider.setContentDescription("拼图验证滑块，用遥控器左右移动");
        form.addView(slider,new LinearLayout.LayoutParams(-1,dp(44)));
        final long[] started={0}; final JSONArray points=new JSONArray();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){ begin(); }
            @Override public void onStopTrackingTouch(SeekBar bar){ }
            private void begin(){ if(started[0]==0){started[0]=System.currentTimeMillis();points.put(point(0,"down",started[0]));} }
            @Override public void onProgressChanged(SeekBar bar,int position,boolean userAction){
                sliderPiece.setTranslationX((float)displayWidth*position/width);
                if(userAction){begin();if(points.length()<4999)points.put(point(position,"move",started[0]));}
            }
        });
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("移动滑块完成验证").setView(form)
            .setNegativeButton("取消",null).setPositiveButton("确认登录",null).create();
        dialog.setOnShowListener(ignored->{
            slider.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(started[0]==0||slider.getProgress()==0){slider.requestFocus();return;}
                points.put(point(slider.getProgress(),"up",started[0]));
                try{
                    JSONObject track=new JSONObject().put("bgImageWidth",width).put("bgImageHeight",height).put("templateImageWidth",pw)
                        .put("templateImageHeight",ph).put("startTime",started[0]).put("stopTime",System.currentTimeMillis()).put("trackList",points);
                    JSONObject p=account("login"); p.put("payload",new JSONObject().put("identifier",user).put("password",password)
                        .put("captchaId",data.getString("id")).put("track",track));
                    dialog.dismiss();
                    call(p,"正在登录…",(out, session)->status.setText("已登录。播放前请刷新分集。"));
                }catch(JSONException invalid){status.setText("验证资料无效");dialog.dismiss();}
            });
        });
        dialog.show();
    }
    private static JSONObject point(int position,String type,long began){
        JSONObject value=new JSONObject();put(value,"x",position);put(value,"y",0);put(value,"type",type);
        put(value,"t",Math.max(0,System.currentTimeMillis()-began));return value;
    }
    private static Bitmap image(String data) throws Exception {
        if(!data.matches("(?s)^data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=\\r\\n]+$") || data.length()>1800000)
            throw new IllegalArgumentException("验证图片无效");
        byte[] bytes=Base64.decode(data.substring(data.indexOf(',')+1),Base64.DEFAULT);
        BitmapFactory.Options probe=new BitmapFactory.Options();probe.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,probe);
        if(probe.outWidth<1 || probe.outHeight<1 || (long)probe.outWidth*probe.outHeight>6_000_000)
            throw new IllegalArgumentException("验证图片尺寸无效");
        Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        if(decoded==null)throw new IllegalArgumentException("验证图片无效");
        return decoded;
    }

    @Override public void onBackPressed() {
        if ("episodes".equals(view)) backToCatalog();
        else if ("catalog".equals(view)) showSources();
        else finish();
    }
    @Override protected void onDestroy() {
        closed=true; revision++;
        if(service!=null){SourcePlaybackActivity.unshare(service);service.close();}
        super.onDestroy();
    }
}
