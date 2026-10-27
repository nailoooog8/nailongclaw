// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;

public class DpadRightTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_right";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_right);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Right button on the remote. Moves focus to the element on the right of the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control right directional key. Moves focus to the element to the right of the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Right";
    }
}
