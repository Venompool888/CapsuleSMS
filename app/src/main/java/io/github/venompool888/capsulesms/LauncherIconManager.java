package io.github.venompool888.capsulesms;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

final class LauncherIconManager {
    private static final String PREFS = "launcher_icon";
    private static final String KEY_STYLE = "style";

    enum Style {
        PRISMATIC_RIM(
                "prismatic_rim", ".LauncherPrismaticRim",
                R.mipmap.ic_launcher_prismatic_rim,
                "七彩流光", "默认 · 深宝石青与七彩边缘"),
        MESSAGE_CURRENT(
                "message_current", ".LauncherMessageCurrent",
                R.mipmap.ic_launcher_message_current,
                "双彩流线", "青绿与珊瑚色短信流线"),
        LIQUID_ELLIPSIS(
                "liquid_ellipsis", ".LauncherLiquidEllipsis",
                R.mipmap.ic_launcher_liquid_ellipsis,
                "液体省略号", "极简三点消息标记");

        final String key;
        final String componentSuffix;
        final int previewResource;
        final String title;
        final String description;

        Style(String key, String componentSuffix, int previewResource,
                String title, String description) {
            this.key = key;
            this.componentSuffix = componentSuffix;
            this.previewResource = previewResource;
            this.title = title;
            this.description = description;
        }

        static Style fromKey(String key) {
            for (Style style : values()) if (style.key.equals(key)) return style;
            return PRISMATIC_RIM;
        }
    }

    private LauncherIconManager() {}

    static Style selected(Context context) {
        return Style.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STYLE, Style.PRISMATIC_RIM.key));
    }

    static void select(Context context, Style selected) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit().putString(KEY_STYLE, selected.key).commit();

        PackageManager packages = context.getPackageManager();
        ComponentName selectedComponent = component(context, selected);
        setState(packages, selectedComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        for (Style style : Style.values()) {
            if (style != selected) {
                setState(packages, component(context, style),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            }
        }
    }

    private static ComponentName component(Context context, Style style) {
        return new ComponentName(context.getPackageName(),
                context.getPackageName() + style.componentSuffix);
    }

    private static void setState(
            PackageManager packages, ComponentName component, int state) {
        if (packages.getComponentEnabledSetting(component) == state) return;
        packages.setComponentEnabledSetting(
                component, state, PackageManager.DONT_KILL_APP);
    }
}
