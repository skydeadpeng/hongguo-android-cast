package io.github.jackotom.hongguo.android;

import java.util.*;

final class BuildConfig { static boolean IS_TV; }
final class Configuration { float fontScale = 1f; Configuration() {} Configuration(float scale) {fontScale=scale;} }
final class Metrics { float density = 1f; int widthPixels=360,heightPixels=800; }
final class Resources {
    Metrics metrics=new Metrics(); Configuration configuration=new Configuration();
    Metrics getDisplayMetrics(){return metrics;} Configuration getConfiguration(){return configuration;}
}
class LayoutActivityBase {
    final Resources resources=new Resources(); int configurationCalls;
    Resources getResources(){return resources;}
    public void onConfigurationChanged(Configuration c){resources.configuration=c;configurationCalls++;}
}
final class Gravity { static final int CENTER_VERTICAL=16; }
class View {
    static View focused;
    static final class MeasureSpec {static final int EXACTLY=0x40000000,UNSPECIFIED=0;static int makeMeasureSpec(int n,int mode){return n|mode;}static int size(int spec){return spec&0x3fffffff;}}
    int width,height,measuredHeight,measuredWidth,minimumHeight,left,top,right,bottom,insetRequests;
    ViewGroup parent; ViewGroup.LayoutParams params=new ViewGroup.LayoutParams(-1,-2); final Object context;
    View(Object value){context=value;}
    float density(){return ((LayoutActivityBase)context).resources.metrics.density;}
    void setPadding(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
    void setMinimumHeight(int h){minimumHeight=h;}
    void setLayoutParams(ViewGroup.LayoutParams p){params=p;}
    ViewGroup.LayoutParams getLayoutParams(){return params;}
    Object getParent(){return parent;}
    int getWidth(){return width;}int getHeight(){return height;}
    int getMeasuredHeight(){return measuredHeight;}
    void measure(int w,int h){measuredWidth=MeasureSpec.size(w);measuredHeight=Math.max(minimumHeight,params.height>=0?params.height:0);}
    View findFocus(){return this==focused?this:null;}
    boolean requestFocus(){focused=this;return true;}
    void requestApplyInsets(){insetRequests++;}
}
class ViewGroup extends View {
    static class LayoutParams {int width,height;LayoutParams(int w,int h){width=w;height=h;}}
    final List<View> children=new ArrayList<>();
    ViewGroup(Object c){super(c);}
    void addView(View child){addView(child,new LayoutParams(-1,-2));}
    void addView(View child,LayoutParams p){if(child.parent!=null)throw new AssertionError("child has old parent");children.add(child);child.parent=this;child.params=p;}
    void removeView(View child){children.remove(child);child.parent=null;}
    void removeAllViews(){for(View child:children)child.parent=null;children.clear();}
    @Override View findFocus(){if(this==focused)return this;for(View child:children){View found=child.findFocus();if(found!=null)return found;}return null;}
}
final class FrameLayout extends ViewGroup {FrameLayout(Object c){super(c);}}
class LinearLayout extends ViewGroup {
    static final int HORIZONTAL=0,VERTICAL=1; int orientation=HORIZONTAL;
    static class LayoutParams extends ViewGroup.LayoutParams {float weight;LayoutParams(int w,int h){super(w,h);}LayoutParams(int w,int h,float value){super(w,h);weight=value;}}
    LinearLayout(Object c){super(c);}
    void setOrientation(int v){orientation=v;}void setGravity(int v){}
    @Override void measure(int widthSpec,int heightSpec){
        int available=Math.max(1,MeasureSpec.size(widthSpec)-left-right),total=0,max=0,fixed=0;float weights=0;
        if(orientation==HORIZONTAL)for(View child:children){LayoutParams p=child.params instanceof LayoutParams?(LayoutParams)child.params:new LayoutParams(child.params.width,child.params.height);if(p.weight>0)weights+=p.weight;else if(p.width>0)fixed+=p.width;}
        for(View child:children){
            LayoutParams p=child.params instanceof LayoutParams?(LayoutParams)child.params:new LayoutParams(child.params.width,child.params.height);
            int allocated=orientation==VERTICAL?available:p.weight>0?Math.max(1,Math.round((available-fixed)*p.weight/weights)):p.width>0?p.width:available;
            child.measure(MeasureSpec.makeMeasureSpec(allocated,MeasureSpec.EXACTLY),0);
            total+=child.measuredHeight;max=Math.max(max,child.measuredHeight);
        }
        measuredWidth=MeasureSpec.size(widthSpec);measuredHeight=Math.max(minimumHeight,top+bottom+(orientation==VERTICAL?total:max));
    }
}
class ScrollView extends ViewGroup {ScrollView(Object c){super(c);}}
class TextView extends View {
    String text="";float textSize=14;int maxLines=1;
    TextView(Object c){super(c);}
    void setTextSize(float n){textSize=n;}void setMaxLines(int n){maxLines=n;}void setEllipsize(Object o){}
    @Override void measure(int widthSpec,int heightSpec){
        float scale=((LayoutActivityBase)context).resources.configuration.fontScale;
        int available=Math.max(1,MeasureSpec.size(widthSpec)-left-right);
        int lines=Math.max(1,Math.min(maxLines,(int)Math.ceil(text.length()*textSize*scale*density()/available)));
        measuredWidth=MeasureSpec.size(widthSpec);measuredHeight=Math.max(minimumHeight,top+bottom+(int)Math.ceil(lines*textSize*scale*density()*1.3));
    }
}
final class Button extends TextView {Button(Object c){super(c);}void setMinHeight(int n){minimumHeight=n;}}
