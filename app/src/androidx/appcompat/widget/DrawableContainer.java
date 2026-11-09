package androidx.appcompat.widget;
import android.graphics.drawable.Drawable;
public class DrawableContainer {
    private final Drawable mDrawable;
    public DrawableContainer(Drawable drawable) { mDrawable = drawable; }
    public Drawable getDrawable() { return mDrawable; }
}
