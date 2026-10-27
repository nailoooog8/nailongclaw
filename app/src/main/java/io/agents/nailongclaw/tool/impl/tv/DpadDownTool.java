// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;

public class DpadDownTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_down";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_down);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Down button on the remote. Moves focus to the element below the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control down directional key. Moves focus to the element below the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_DOWN;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Down";
    }
}
