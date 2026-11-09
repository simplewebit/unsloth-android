package androidx.core.widget;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
public interface TintableBackgroundView {
    void setSupportBackgroundTintList(ColorStateList tint);
    ColorStateList getSupportBackgroundTintList();
    void setSupportBackgroundTintMode(PorterDuff.Mode tintMode);
    PorterDuff.Mode getSupportBackgroundTintMode();
}
