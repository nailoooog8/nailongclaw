// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;

public class VolumeUpTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "volume_up";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_volume_up);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Volume Up button to increase the volume.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the volume up key to increase volume.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_VOLUME_UP;
    }

    @Override
    protected String getKeyLabel() {
        return "Volume Up";
    }
}
