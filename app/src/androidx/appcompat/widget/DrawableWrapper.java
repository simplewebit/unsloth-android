package androidx.appcompat.widget;
import android.graphics.drawable.Drawable;
public class DrawableWrapper extends Drawable {
    private final Drawable mDrawable;
    public DrawableWrapper(Drawable drawable) { mDrawable = drawable; }
    @Override public void draw(android.graphics.Canvas canvas) { mDrawable.draw(canvas); }
    @Override public void setAlpha(int alpha) { mDrawable.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter cf) { mDrawable.setColorFilter(cf); }
    @Override public int getOpacity() { return mDrawable.getOpacity(); }
}
