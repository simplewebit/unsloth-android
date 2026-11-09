package androidx.appcompat.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

public class ResourceManagerInternal {
    public interface ResourceManagerHooks {
        void onTintModeChanged(PorterDuff.Mode mode);
        void onTintChanged(ColorStateList tint);
    }

    private static ResourceManagerInternal sInstance;

    public static synchronized ResourceManagerInternal get() {
        if (sInstance == null) sInstance = new ResourceManagerInternal();
        return sInstance;
    }

    public ResourceManagerInternal() {}

    public void setHooks(ResourceManagerHooks hooks) {}

    public Drawable getDrawable(Context context, int id) {
        try { return context.getResources().getDrawable(id); } catch (Exception e) { return null; }
    }

    public Drawable getDrawable(Context context, int id, boolean useTheme) {
        return getDrawable(context, id);
    }

    public void onConfigurationChanged(Context context) {}

    public Drawable onDrawableLoadedFromResources(Context context, VectorEnabledTintResources resources, int id) {
        return null;
    }

    public boolean tintDrawableUsingColorFilter(Context context, int id, Drawable drawable) {
        return false;
    }

    public ColorStateList getTintList(Context context, int id) {
        return null;
    }

    public static void tintDrawable(Drawable drawable, TintInfo tintInfo, int[] themeAttrs) {}

    public static PorterDuffColorFilter getPorterDuffColorFilter(int color, PorterDuff.Mode mode) {
        return new PorterDuffColorFilter(color, mode);
    }
}
