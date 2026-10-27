// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.tv;

import android.view.KeyEvent;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;

public class PressMenuTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "press_menu";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_press_menu);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Menu button on the remote. Opens context menu or settings in the current app.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control menu key. Opens the context menu or settings in the current app.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_MENU;
    }

    @Override
    protected String getKeyLabel() {
        return "Menu";
    }
}
