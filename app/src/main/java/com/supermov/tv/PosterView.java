package com.supermov.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** 海报图：测量期强制 2:3 竖版比例，杜绝回收复用后高度不一致的问题。 */
public class PosterView extends ImageView {

    public PosterView(Context c) { super(c); }
    public PosterView(Context c, AttributeSet a) { super(c, a); }
    public PosterView(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms);
        setMeasuredDimension(w, Math.round(w * 1.5f));
    }
}
