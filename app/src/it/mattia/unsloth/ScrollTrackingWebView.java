package it.mattia.unsloth;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.WebView;

/**
 * WebView che notifica i cambiamenti di scroll: lo stub android.jar della
 * toolchain non espone setOnScrollChanged, quindi si overridea il metodo
 * protetto onScrollChanged.
 */
public class ScrollTrackingWebView extends WebView {

    public interface OnScrollListener {
        void onScroll(int newScrollX, int newScrollY, int oldScrollY, int scrollYRange);
    }

    private OnScrollListener listener;

    public ScrollTrackingWebView(Context context) {
        super(context);
    }

    public ScrollTrackingWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnScrollListener(OnScrollListener l) {
        this.listener = l;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldh, int h) {
        super.onScrollChanged(l, t, oldh, h);
        if (listener != null) {
            listener.onScroll(l, t, oldh, h);
        }
    }
}

