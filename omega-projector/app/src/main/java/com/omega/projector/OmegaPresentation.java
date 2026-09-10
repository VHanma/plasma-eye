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
    private final ProjectionEngineView.Snapshot snapshot;
    private ProjectionEngineView view;

    public OmegaPresentation(Context outerContext, Display display, Uri uri, boolean video, ProjectionEngineView.Snapshot snapshot) {
        super(outerContext, display);
        this.uri = uri;
        this.video = video;
        this.snapshot = snapshot;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new ProjectionEngineView(getContext());
        setContentView(view);
        view.applySnapshot(snapshot);
        if (uri != null) view.setMedia(uri, video);
        else view.setTestPattern();
    }

    @Override protected void onStop() {
        if (view != null) view.releaseMedia();
        super.onStop();
    }
}
