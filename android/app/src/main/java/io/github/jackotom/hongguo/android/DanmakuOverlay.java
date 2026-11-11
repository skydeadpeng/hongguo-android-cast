// SPDX-License-Identifier: GPL-3.0-only
package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.List;

/** Non-interactive display between VideoView and HUD. All movement follows the decoder's clock. */
final class DanmakuOverlay extends View {
    interface Clock { long position(); boolean playing(); }
    private final Clock clock;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DanmakuTimeline timeline=new DanmakuTimeline();
    private boolean enabled;
    DanmakuOverlay(Context context,Clock clock){super(context);this.clock=clock;setFocusable(false);setClickable(false);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);paint.setTextSize(18*getResources().getDisplayMetrics().scaledDensity);paint.setShadowLayer(2,1,1,0xff000000);paint.setAlpha(220);}
    void items(List<DanmakuClient.Item> items,long position){timeline.replace(items,position);invalidate();}
    void clear(){timeline.clear();invalidate();}
    void enabled(boolean value){enabled=value;if(!value)timeline.clear();invalidate();}
    void pulse(){if(enabled)invalidate();}
    void seek(long position){timeline.seek(position);invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(!enabled)return;long position;boolean playing;try{position=clock.position();playing=clock.playing();}catch(RuntimeException notReady){return;}
        float size=paint.getTextSize(),top=Math.max(size*2,getHeight()*.13f),step=size*1.5f;
        for(DanmakuTimeline.Visible item:timeline.advance(position,playing,getWidth(),paint::measureText)){paint.setColor(item.item.color);paint.setAlpha(220);canvas.drawText(item.item.text,item.x(position,getWidth()),top+item.lane*step,paint);}
        if(playing&&getWindowVisibility()==View.VISIBLE)postInvalidateDelayed(33);
    }
}
