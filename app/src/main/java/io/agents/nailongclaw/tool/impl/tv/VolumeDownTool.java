// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;

public class VolumeDownTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "volume_down";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_volume_down);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Volume Down button to decrease the volume.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the volume down key to decrease volume.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    @Override
    protected String getKeyLabel() {
        return "Volume Down";
    }
}
