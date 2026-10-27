// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;

public class DpadUpTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_up";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_up);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Up button on the remote. Moves focus to the element above the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control up directional key. Moves focus to the element above the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_UP;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Up";
    }
}
