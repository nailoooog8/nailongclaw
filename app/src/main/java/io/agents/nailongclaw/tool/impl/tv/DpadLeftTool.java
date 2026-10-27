// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;

public class DpadLeftTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_left";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_left);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Left button on the remote. Moves focus to the element on the left of the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control left directional key. Moves focus to the element to the left of the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_LEFT;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Left";
    }
}
