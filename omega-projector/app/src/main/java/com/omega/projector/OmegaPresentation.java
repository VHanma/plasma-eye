package com.omega.projector;

import android.app.Presentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;

public class OmegaPresentation extends Presentation {
    private final Uri uri;
    private final boolean video;
    private final OmegaGLView.Snapshot snapshot;
    private OmegaGLView view;

    public OmegaPresentation(Context outerContext, Display display, Uri uri, boolean video, OmegaGLView.Snapshot snapshot) {
        super(outerContext, display);
        this.uri = uri;
        this.video = video;
        this.snapshot = snapshot;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new OmegaGLView(getContext());
        setContentView(view);
        view.applySnapshot(snapshot);
        view.setMedia(uri, video);
    }

    @Override
    protected void onStop() {
        if (view != null) view.releaseMedia();
        super.onStop();
    }
}
