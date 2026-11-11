// SPDX-License-Identifier: GPL-3.0-only
package io.github.jackotom.hongguo.android;

import java.util.*;

/** Pure media-time scheduler. It never uses wall time, resumes past a seek, or fills a paused frame. */
final class DanmakuTimeline {
    interface Measure { float width(String text); }
    static final int LANES=4, MAX_VISIBLE=20;
    static final class Visible {
        final DanmakuClient.Item item; final int lane; final float width;
        Visible(DanmakuClient.Item item,int lane,float width){this.item=item;this.lane=lane;this.width=width;}
        float x(long position,float screen){return screen-(position-item.timeMs)*velocity(screen);}
    }
    private List<DanmakuClient.Item> items=Collections.emptyList();
    private final List<Visible> active=new ArrayList<>();
    private final Set<String> emitted=new HashSet<>();
    private int cursor;
    private long last=-1;
    static float velocity(float width){return Math.max(.10f,width/8000f);}
    void clear(){items=Collections.emptyList();active.clear();emitted.clear();cursor=0;last=-1;}
    void replace(List<DanmakuClient.Item> input,long position){items=new ArrayList<>(input);items.sort(Comparator.comparingLong(x->x.timeMs));cursor=lowerBound(Math.max(0,position-750));if(emitted.size()>3000)emitted.clear();}
    private int lowerBound(long position){int low=0,high=items.size();while(low<high){int middle=(low+high)>>>1;if(items.get(middle).timeMs<position)low=middle+1;else high=middle;}return low;}
    void seek(long position){active.clear();emitted.clear();cursor=lowerBound(Math.max(0,position));last=position;}
    List<Visible> advance(long position,boolean playing,float screen,Measure measure){
        if(position<0||screen<=0)return Collections.emptyList();
        if(last>=0&&(position<last-200||position>last+3000))seek(position);
        last=position;
        active.removeIf(x->position<x.item.timeMs||x.x(position,screen)+x.width<0||position-x.item.timeMs>40000);
        if(playing){int burst=0;while(cursor<items.size()&&items.get(cursor).timeMs<=position){DanmakuClient.Item item=items.get(cursor++);String key=item.id.isEmpty()?item.timeMs+":"+item.text:item.id;if(position-item.timeMs>750||!emitted.add(key))continue;if(active.size()>=MAX_VISIBLE||burst>=LANES)continue;
                int selected=-1;for(int lane=0;lane<LANES;lane++){boolean room=true;for(Visible existing:active)if(existing.lane==lane&&existing.x(position,screen)+existing.width>screen-32){room=false;break;}if(room){selected=lane;break;}}
                if(selected>=0){active.add(new Visible(item,selected,Math.max(1,measure.width(item.text))));burst++;}
            }}
        return new ArrayList<>(active);
    }
    int activeCount(){return active.size();}
}
