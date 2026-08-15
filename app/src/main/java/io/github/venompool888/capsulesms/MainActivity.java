package io.github.venompool888.capsulesms;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.provider.ContactsContract;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.text.format.DateUtils;
import android.text.format.DateFormat;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.venompool888.capsulesms.sms.SmsModels;
import io.github.venompool888.capsulesms.sms.SmsRepository;
import io.github.venompool888.capsulesms.sms.SmsSender;
import io.github.venompool888.capsulesms.sms.CloudMessageOriginStore;
import io.github.venompool888.capsulesms.sms.TrashCleanupScheduler;
import io.github.venompool888.capsulesms.sms.TrashStore;
import io.github.venompool888.capsulesms.sms.VerificationAnnotationStore;
import io.github.venompool888.capsulesms.sync.AdvancedSettingsActivity;
import io.github.venompool888.capsulesms.sync.CloudSyncScheduler;
import io.github.venompool888.capsulesms.sync.CloudSyncEngine;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

public final class MainActivity extends Activity {
    private static final int REQUEST_ROLE = 100;
    private static final int REQUEST_PERMISSIONS = 101;
    private static final int REQUEST_CONTACT = 102;
    private static final int EDGE_SCROLLBAR_GUTTER_DP = 16;
    private static final int CONVERSATION_SCROLLBAR_GUTTER_DP = 4;
    private static final int MESSAGE_EDGE_MARGIN_DP = 8;
    private static final float MESSAGE_MAX_WIDTH_FRACTION = 0.82f;
    private static final String ACTION_MANUAL_VERIFICATION =
            "io.github.venompool888.capsulesms.MANUAL_VERIFICATION";
    private static final String SNAPSHOT_PREFS = "inbox_snapshot";
    private static final String SNAPSHOT_KEY = "conversations";
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private UiPalette palette;
    private TypographyPreferences typography;
    private String appliedTypographySignature;
    private LinearLayout root;
    private String currentAddress;
    private List<SmsModels.Conversation> inboxConversations;
    private final Map<String, ContactIdentity> contactIdentities = new HashMap<>();
    private final Map<Integer, Integer> subscriptionSlots = new HashMap<>();
    private final List<SimChoice> simChoices = new ArrayList<>();
    private int selectedSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private CloudMessageOriginStore.Origin currentRemoteOrigin;
    private EditText activeComposerBody;
    private EditText composerAddressInput;
    private FrameLayout inboxFrame;
    private ListView inboxList;
    private String inboxScrollAnchorAddress;
    private int inboxScrollAnchorOffset;
    private View inboxLoading;
    private int inboxGeneration;
    private boolean screenInitialized;
    private boolean defaultSmsStateKnown;
    private boolean lastDefaultSmsState;
    private boolean showingTrash;
    private boolean smsObserverRegistered;
    private int providerRefreshGeneration;
    private final List<View> cachedInboxViews = new ArrayList<>();
    private final Map<String, List<SmsModels.Message>> messageCache = new ConcurrentHashMap<>();
    private final Set<Long> selectedMessageIds = new LinkedHashSet<>();
    private final Map<Long, View> messageRows = new HashMap<>();
    private TextView conversationTitle;
    private TextView conversationSelect;
    private ImageButton conversationDelete;
    private LinearLayout conversationMessages;
    private ScrollView conversationScroll;
    private boolean messageSelectionMode;
    private VerificationAnnotationStore verificationAnnotations;
    private int hiddenSettingsTapCount;
    private long hiddenSettingsLastTapAt;
    private final OnBackInvokedCallback backCallback = this::handleBack;
    private final ContentObserver smsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange, Uri uri) {
            refreshInboxFromProvider();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = UiPalette.from(this);
        typography = new TypographyPreferences(this);
        appliedTypographySignature = typography.signature();
        palette.applySystemBars(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipToPadding(false);
        root.setBackgroundColor(palette.background);
        applySystemBarInsets();
        setContentView(root);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        verificationAnnotations = new VerificationAnnotationStore(this);
        handleLaunchIntent(getIntent());
        new TrashStore(this).purgeExpired();
        TrashCleanupScheduler.schedule(this);
        CloudSyncScheduler.schedulePeriodic(this);
        ensureSmsRole();
    }

    private void applySystemBarInsets() {
        int horizontalPadding = dp(16);
        int verticalPadding = dp(8);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets systemBars = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            boolean keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
            int bottomInset = keyboardVisible
                    ? Math.max(systemBars.bottom, ime.bottom) : systemBars.bottom;
            view.setPadding(
                    horizontalPadding + systemBars.left,
                    verticalPadding + systemBars.top,
                    horizontalPadding + systemBars.right,
                    verticalPadding + bottomInset);
            if (keyboardVisible) {
                if (activeComposerBody != null && activeComposerBody.hasFocus()) {
                    view.post(this::scrollConversationToBottom);
                } else {
                    view.postDelayed(() -> {
                    WindowInsets current = view.getRootWindowInsets();
                    if (current == null || !current.isVisible(WindowInsets.Type.ime())) return;
                    if (activeComposerBody == null || !activeComposerBody.isAttachedToWindow()
                            || !activeComposerBody.hasFocus()) {
                        android.view.WindowInsetsController controller =
                                view.getWindowInsetsController();
                        if (controller != null) controller.hide(WindowInsets.Type.ime());
                        return;
                    }
                    scrollConversationToBottom();
                    }, 220L);
                }
            }
            return insets;
        });
        root.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        root.requestApplyInsets();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
        if (screenInitialized && currentAddress != null && !currentAddress.isBlank()
                && !ACTION_MANUAL_VERIFICATION.equals(intent.getAction())) {
            showConversation(currentAddress);
            return;
        }
        if (ACTION_MANUAL_VERIFICATION.equals(intent.getAction())) {
            inboxConversations = null;
            cachedInboxViews.clear();
            if (screenInitialized) showInbox();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        String typographySignature = typography.signature();
        boolean typographyChanged = !typographySignature.equals(appliedTypographySignature);
        appliedTypographySignature = typographySignature;
        boolean isDefaultSms = isDefaultSmsApp();
        boolean defaultSmsStateChanged = defaultSmsStateKnown
                && lastDefaultSmsState != isDefaultSms;
        defaultSmsStateKnown = true;
        lastDefaultSmsState = isDefaultSms;
        if (defaultSmsStateChanged) cachedInboxViews.clear();
        if (!screenInitialized) {
            screenInitialized = true;
            if (currentAddress == null) showInbox(); else showConversation(currentAddress);
        } else if (typographyChanged && !showingTrash) {
            cachedInboxViews.clear();
            if (currentAddress == null) showInbox(); else showConversation(currentAddress);
        } else if (defaultSmsStateChanged && currentAddress == null && !showingTrash) {
            showInbox();
        } else {
            refreshInboxFromProvider();
        }
        CloudSyncScheduler.scheduleImmediate(this);
        CloudSyncScheduler.startRealtime(this);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!smsObserverRegistered) {
            getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver);
            smsObserverRegistered = true;
        }
    }

    @Override protected void onStop() {
        if (smsObserverRegistered) {
            getContentResolver().unregisterContentObserver(smsObserver);
            smsObserverRegistered = false;
        }
        super.onStop();
    }

    private void handleBack() {
        if (messageSelectionMode) {
            clearMessageSelection();
        } else if (showingTrash) {
            showInbox();
        } else if (currentAddress != null) {
            currentAddress = null;
            showInbox();
        } else {
            finish();
        }
    }

    private void handleLaunchIntent(Intent intent) {
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && ACTION_MANUAL_VERIFICATION.equals(intent.getAction())) {
            verificationAnnotations.put(
                    intent.getLongExtra("message_id", -1), intent.getStringExtra("code"));
            return;
        }
        Uri data = intent.getData();
        if (data != null && ("sms".equals(data.getScheme()) || "smsto".equals(data.getScheme())
                || "mms".equals(data.getScheme()) || "mmsto".equals(data.getScheme()))) {
            currentAddress = data.getSchemeSpecificPart();
        }
    }

    private void ensureSmsRole() {
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles.isRoleAvailable(RoleManager.ROLE_SMS) && !roles.isRoleHeld(RoleManager.ROLE_SMS)) {
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_SMS), REQUEST_ROLE);
        } else {
            requestRuntimePermissions();
        }
    }

    private void requestRuntimePermissions() {
        String[] candidates = {
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS
        };
        List<String> missing = new ArrayList<>();
        for (String permission : candidates) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && currentAddress == null) showInbox();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ROLE) {
            if (resultCode == RESULT_OK) requestRuntimePermissions();
            else Toast.makeText(this, "设为默认短信应用后才能完整收发短信", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_CONTACT && resultCode == RESULT_OK && data != null) {
            fillRecipientFromContact(data.getData());
        }
    }

    private void showInbox() {
        showingTrash = false;
        messageSelectionMode = false;
        selectedMessageIds.clear();
        currentAddress = null;
        currentRemoteOrigin = null;
        activeComposerBody = null;
        if (!cachedInboxViews.isEmpty()) {
            root.removeAllViews();
            for (View view : cachedInboxViews) root.addView(view);
            cachedInboxViews.clear();
            animateScreenIn(-1);
            return;
        }
        inboxFrame = null;
        int generation = ++inboxGeneration;
        if (inboxConversations == null) inboxConversations = loadInboxSnapshot();
        refreshSubscriptionSlots();
        root.removeAllViews();
        LinearLayout header = row();
        header.setPadding(0, dp(6), 0, dp(14));

        ImageButton menu = new ImageButton(this);
        menu.setImageResource(android.R.drawable.ic_menu_sort_by_size);
        menu.setColorFilter(palette.textPrimary);
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setContentDescription("菜单");
        menu.setOnClickListener(this::showInboxMenu);
        header.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(46)));

        EditText search = new EditText(this);
        search.setHint("搜索短信");
        setInboxTextSize(search, 17);
        search.setTextColor(palette.textPrimary);
        search.setHintTextColor(palette.textMuted);
        search.setSingleLine(true);
        search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(10));
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(rounded(palette.surfaceMuted, 28));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (inboxConversations != null) {
                    renderConversationFrame(filterConversations(value.toString()), false);
                }
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        header.addView(search, searchParams);
        root.addView(header);

        if (!isDefaultSmsApp()) {
            TextView banner = text("设为默认短信应用，才能读取、接收和保存短信。点此继续", 14, Color.WHITE);
            banner.setPadding(dp(14), dp(12), dp(14), dp(12));
            banner.setBackgroundColor(palette.violet);
            banner.setOnClickListener(v -> ensureSmsRole());
            root.addView(banner, matchWrap(dp(8)));
        }

        inboxLoading = null;
        renderConversationFrame(inboxConversations);
        animateScreenIn(-1);

        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (inboxLoading instanceof TextView) {
                ((TextView) inboxLoading).setText("需要短信读取权限");
            }
            return;
        }
        io.execute(() -> {
            List<SmsModels.Conversation> conversations = new SmsRepository(this).conversations();
            saveInboxSnapshot(conversations);
            Map<String, ContactIdentity> contacts = loadContactIdentities(
                    conversations.subList(0, Math.min(12, conversations.size())));
            runOnUiThread(() -> {
                if (generation != inboxGeneration || currentAddress != null) return;
                boolean conversationsChanged = !sameConversations(inboxConversations, conversations);
                boolean contactsChanged = !sameContacts(contactIdentities, contacts);
                inboxConversations = conversations;
                contactIdentities.clear();
                contactIdentities.putAll(contacts);
                if (conversationsChanged || contactsChanged) renderConversationFrame(conversations);
                prefetchRecentMessages(conversations);
            });
        });
    }

    private List<SmsModels.Conversation> loadInboxSnapshot() {
        String raw = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE).getString(SNAPSHOT_KEY, null);
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        List<SmsModels.Conversation> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(raw);
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                CloudMessageOriginStore.Origin origin = value.has("sourceDeviceId")
                        ? new CloudMessageOriginStore.Origin(
                                value.optString("sourceDeviceId", null),
                                value.optString("sourceDeviceName", null),
                                value.optInt("sourceSubscriptionId", -1),
                                value.optString("sourceLineNumber", null),
                                value.optInt("sourceSimSlot", -1))
                        : (value.isNull("sourceDeviceName") ? null
                                : CloudMessageOriginStore.Origin.deviceOnly(
                                        value.optString("sourceDeviceName", null)));
                result.add(new SmsModels.Conversation(
                        value.getString("address"), value.optString("preview", ""),
                        value.getLong("date"), value.optInt("unread", 0),
                        value.optInt("subscriptionId", SubscriptionManager.INVALID_SUBSCRIPTION_ID),
                        value.optLong("latestMessageId", -1),
                        value.optInt("latestMessageType", Telephony.Sms.MESSAGE_TYPE_INBOX), origin));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private void saveInboxSnapshot(List<SmsModels.Conversation> conversations) {
        JSONArray values = new JSONArray();
        int count = Math.min(500, conversations.size());
        for (int i = 0; i < count; i++) {
            SmsModels.Conversation conversation = conversations.get(i);
            try {
                JSONObject value = new JSONObject()
                        .put("address", conversation.address())
                        .put("preview", conversation.preview())
                        .put("date", conversation.date())
                        .put("unread", conversation.unread())
                        .put("subscriptionId", conversation.subscriptionId())
                        .put("latestMessageId", conversation.latestMessageId())
                        .put("latestMessageType", conversation.latestMessageType())
                        .put("sourceDeviceName", conversation.sourceDeviceName());
                if (conversation.origin() != null) {
                    value.put("sourceDeviceId", conversation.origin().deviceId)
                            .put("sourceSubscriptionId", conversation.origin().subscriptionId)
                            .put("sourceLineNumber", conversation.origin().lineNumber)
                            .put("sourceSimSlot", conversation.origin().simSlot);
                }
                values.put(value);
            } catch (Exception ignored) {}
        }
        getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
                .edit().putString(SNAPSHOT_KEY, values.toString()).apply();
    }

    private boolean sameConversations(
            List<SmsModels.Conversation> first, List<SmsModels.Conversation> second) {
        if (first == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            SmsModels.Conversation left = first.get(i);
            SmsModels.Conversation right = second.get(i);
            if (!left.address().equals(right.address())
                    || !left.preview().equals(right.preview())
                    || left.date() != right.date() || left.unread() != right.unread()
                    || left.subscriptionId() != right.subscriptionId()
                    || left.latestMessageId() != right.latestMessageId()
                    || left.latestMessageType() != right.latestMessageType()
                    || !sameOrigin(left.origin(), right.origin())) return false;
        }
        return true;
    }

    private boolean sameContacts(
            Map<String, ContactIdentity> first, Map<String, ContactIdentity> second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (String key : first.keySet()) {
            ContactIdentity left = first.get(key);
            ContactIdentity right = second.get(key);
            String leftName = left == null ? null : left.name;
            String rightName = right == null ? null : right.name;
            if (!java.util.Objects.equals(leftName, rightName)) return false;
            if ((left == null || left.photo == null) != (right == null || right.photo == null)) return false;
        }
        return true;
    }

    private List<SmsModels.Conversation> filterConversations(String query) {
        String needle = query.trim().toLowerCase(Locale.getDefault());
        if (needle.isEmpty()) return inboxConversations;
        List<SmsModels.Conversation> filtered = new ArrayList<>();
        for (SmsModels.Conversation conversation : inboxConversations) {
            if (displayName(conversation.address()).toLowerCase(Locale.getDefault()).contains(needle)
                    || conversation.address().toLowerCase(Locale.getDefault()).contains(needle)
                    || conversation.preview().toLowerCase(Locale.getDefault()).contains(needle)) {
                filtered.add(conversation);
            }
        }
        return filtered;
    }

    private void renderConversationFrame(List<SmsModels.Conversation> conversations) {
        renderConversationFrame(conversations, true);
    }

    private void renderConversationFrame(
            List<SmsModels.Conversation> conversations, boolean animateRows) {
        captureInboxScrollPosition();
        if (inboxFrame != null) root.removeView(inboxFrame);
        FrameLayout frame = new FrameLayout(this);
        inboxFrame = frame;
        ListView list = new ListView(this);
        inboxList = list;
        list.setClipToPadding(false);
        list.setPadding(0, 0, dp(EDGE_SCROLLBAR_GUTTER_DP), dp(88));
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        boolean[] revealedRows = new boolean[animateRows ? Math.min(10, conversations.size()) : 0];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return conversations.size(); }
            @Override public SmsModels.Conversation getItem(int position) {
                return conversations.get(position);
            }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = createConversationRow(getItem(position));
                if (position < revealedRows.length && !revealedRows[position]) {
                    revealedRows[position] = true;
                    animateConversationRow(row, position);
                }
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) ->
                showConversation(conversations.get(position).address()));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmTrashConversation(conversations.get(position));
            return true;
        });
        TextView empty = text(inboxConversations != null && inboxConversations.isEmpty()
                ? "这里还没有短信\n点右下角开始一段新对话"
                : "没有找到相关短信", 17, palette.textMuted);
        empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        frame.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrollbarThumb = new View(this);
        scrollbarThumb.setBackground(rounded(palette.scrollThumb, 2));
        scrollbarThumb.setElevation(dp(12));
        scrollbarThumb.setAlpha(0f);
        scrollbarThumb.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams scrollbarParams = new FrameLayout.LayoutParams(
                dp(2), dp(44), Gravity.END | Gravity.TOP);
        scrollbarParams.setMargins(0, 0, dp(2), 0);
        frame.addView(scrollbarThumb, scrollbarParams);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                scrollbarThumb.animate().cancel();
                if (state == SCROLL_STATE_IDLE) {
                    scrollbarThumb.animate().alpha(0f).setStartDelay(650L).setDuration(250L)
                            .withEndAction(() -> scrollbarThumb.setVisibility(View.INVISIBLE))
                            .start();
                } else {
                    scrollbarThumb.setVisibility(View.VISIBLE);
                    scrollbarThumb.setAlpha(1f);
                }
            }

            @Override public void onScroll(
                    AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                updateInboxScrollbar(scrollbarThumb, view, firstVisible, visibleCount, totalCount);
            }
        });
        restoreInboxScrollPosition(list, conversations);

        ImageButton compose = new ImageButton(this);
        compose.setImageResource(android.R.drawable.ic_input_add);
        compose.setColorFilter(Color.WHITE);
        compose.setBackground(rounded(palette.accent, 30));
        compose.setElevation(dp(10));
        compose.setContentDescription("新短信");
        compose.setOnClickListener(v -> showComposer());
        FrameLayout.LayoutParams composeParams = new FrameLayout.LayoutParams(dp(58), dp(58),
                Gravity.END | Gravity.BOTTOM);
        composeParams.setMargins(0, 0, dp(8 + EDGE_SCROLLBAR_GUTTER_DP), dp(16));
        frame.addView(compose, composeParams);
        if (motionEnabled()) {
            compose.setAlpha(0f);
            compose.setScaleX(0.72f);
            compose.setScaleY(0.72f);
            compose.post(() -> compose.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(90L).setDuration(260L)
                    .setInterpolator(new OvershootInterpolator(0.9f)).start());
        }
        // Extend only the inbox frame through the root's right content inset to the screen edge.
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels - root.getPaddingLeft(), 0, 1);
        root.addView(frame, frameParams);
    }

    private void updateInboxScrollbar(View thumb, AbsListView list, int firstVisible,
            int visibleCount, int totalCount) {
        int trackHeight = list.getHeight();
        if (trackHeight <= 0 || visibleCount <= 0 || totalCount <= visibleCount) {
            // Keep the gutter measured during the first layout; GONE would leave a 0px thumb.
            thumb.setVisibility(View.INVISIBLE);
            return;
        }
        thumb.setVisibility(View.VISIBLE);
        thumb.bringToFront();
        int thumbHeight = Math.max(dp(44), Math.round(
                trackHeight * (visibleCount / (float) totalCount)));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        if (params.height != thumbHeight) {
            params.height = thumbHeight;
            thumb.setLayoutParams(params);
        }
        float progress = firstVisible / (float) Math.max(1, totalCount - visibleCount);
        thumb.setTranslationY(Math.round((trackHeight - thumbHeight) * progress));
    }

    private View createConversationRow(SmsModels.Conversation conversation) {
        LinearLayout item = row();
        item.setGravity(Gravity.TOP);
        int itemPadding = typography.inboxItemVerticalPaddingDp();
        item.setMinimumHeight(dp(54 + itemPadding * 2));
        item.setPadding(dp(2), dp(itemPadding), dp(2), dp(itemPadding));

        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackground(rounded(avatarColor(conversation.address()), 28));
        ContactIdentity identity = contactIdentities.get(conversation.address());
        if (identity != null && identity.photo != null) {
            ImageView photo = new ImageView(this);
            photo.setImageBitmap(identity.photo);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo.setClipToOutline(true);
            photo.setBackground(rounded(Color.TRANSPARENT, 28));
            photo.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            avatar.addView(photo, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        } else {
            TextView mark = text(initial(displayName(conversation.address())), 22, Color.WHITE);
            mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            mark.setGravity(Gravity.CENTER);
            avatar.addView(mark, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        }
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        avatarParams.setMargins(0, dp(1), 0, 0);
        item.addView(avatar, avatarParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, dp(8), 0);
        TextView address = inboxText(
                displayName(conversation.address()), 18, palette.textPrimary);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        address.setTypeface(Typeface.DEFAULT, conversation.unread() > 0 ? Typeface.BOLD : Typeface.NORMAL);
        String previewText = conversation.origin() == null
                ? conversation.preview()
                : (conversation.latestIsOutgoing() ? "通过 " : "来自 ")
                        + conversation.origin().displayLabel() + " · " + conversation.preview();
        TextView preview = inboxText(previewText, 15, conversation.unread() > 0
                ? palette.textSecondary : palette.textMuted);
        preview.setMaxLines(2);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preview.setIncludeFontPadding(false);
        preview.setLineSpacing(0, typography.inboxLineSpacingMultiplier());
        preview.setTypeface(Typeface.DEFAULT, conversation.unread() > 0 ? Typeface.BOLD : Typeface.NORMAL);
        copy.addView(address);
        copy.addView(preview);
        VerificationAnnotationStore.Annotation verification =
                conversation.latestIsOutgoing() ? null : verificationAnnotations.resolve(
                        conversation.latestMessageId(), conversation.preview());
        if (verification != null) copy.addView(verificationCard(verification));
        item.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.END | Gravity.TOP);
        TextView time = inboxText(inboxTime(conversation.date()), 13, conversation.unread() > 0
                ? palette.textSecondary : palette.textMuted);
        time.setGravity(Gravity.END);
        meta.addView(time);
        LinearLayout status = row();
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Integer slot = subscriptionSlots.get(conversation.subscriptionId());
        if (slot != null) {
            TextView sim = inboxText("SIM " + slot, 9, slot == 1
                    ? palette.verificationText : palette.violetText);
            sim.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            sim.setGravity(Gravity.CENTER);
            sim.setPadding(dp(6), dp(2), dp(6), dp(2));
            sim.setBackground(rounded(slot == 1
                    ? palette.verificationSurface : palette.violetSurface, 6));
            status.addView(sim, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(18)));
        }
        if (conversation.unread() > 0) {
            View dot = new View(this);
            dot.setBackground(rounded(avatarColor(conversation.address()), 7));
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(13), dp(13));
            dotParams.setMargins(dp(5), 0, 0, 0);
            status.addView(dot, dotParams);
        }
        if (status.getChildCount() > 0) {
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.setMargins(0, dp(7), 0, 0);
            meta.addView(status, statusParams);
        }
        item.addView(meta, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        return item;
    }

    private View verificationCard(VerificationAnnotationStore.Annotation verification) {
        LinearLayout card = row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(7), dp(3), dp(9), dp(3));
        card.setBackground(rounded(palette.verificationSurface, 10));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_copy_code);
        icon.setContentDescription("复制验证码");
        card.addView(icon, new LinearLayout.LayoutParams(dp(17), dp(17)));
        TextView code = text(verification.code(), 16, palette.verificationText);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setLetterSpacing(0.06f);
        code.setSingleLine(true);
        code.setPadding(dp(7), 0, 0, 0);
        card.addView(code);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, 0);
        card.setLayoutParams(params);
        card.setContentDescription("验证短信，验证码 " + verification.code() + "，点按复制");
        card.setOnClickListener(v -> copyVerificationCode(verification.code(), card));
        return card;
    }

    private void copyVerificationCode(String code, View feedbackView) {
        putVerificationCodeOnClipboard(code);
        pulse(feedbackView);
        Toast.makeText(this, "验证码已复制", Toast.LENGTH_SHORT).show();
    }

    private void copyInlineVerificationCode(String code, TextView bubble,
            VerificationCodeSpan codeSpan) {
        putVerificationCodeOnClipboard(code);
        springCode(bubble, codeSpan);
        Toast.makeText(this, "验证码已复制", Toast.LENGTH_SHORT).show();
    }

    private void putVerificationCodeOnClipboard(String code) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("验证码", code));
    }

    private void showComposer() {
        showingTrash = false;
        selectedMessageIds.clear();
        stashInboxViews();
        currentAddress = "";
        currentRemoteOrigin = null;
        activeComposerBody = null;
        root.removeAllViews();
        addConversationHeader("新短信");
        TextView recipientLabel = text("发送给", 13, palette.textSecondary);
        recipientLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        recipientLabel.setPadding(dp(4), dp(12), 0, dp(7));
        root.addView(recipientLabel);
        LinearLayout recipient = row();
        recipient.setGravity(Gravity.CENTER_VERTICAL);
        recipient.setPadding(dp(14), 0, dp(6), 0);
        recipient.setBackground(rounded(palette.surfaceMuted, 22));
        EditText address = new EditText(this);
        composerAddressInput = address;
        address.setHint("输入姓名或手机号码");
        setConversationTextSize(address, 16);
        address.setTextColor(palette.textPrimary);
        address.setHintTextColor(palette.textMuted);
        address.setSingleLine(true);
        address.setPadding(dp(4), 0, dp(8), 0);
        address.setBackgroundColor(Color.TRANSPARENT);
        address.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        address.setMinHeight(dp(52));
        recipient.addView(address, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button contacts = new Button(this);
        contacts.setText("通讯录");
        contacts.setTextSize(14);
        contacts.setTextColor(palette.accentText);
        contacts.setAllCaps(false);
        contacts.setBackground(rounded(palette.accentSurface, 18));
        contacts.setOnClickListener(v -> openContactPicker());
        recipient.addView(contacts, new LinearLayout.LayoutParams(dp(78), dp(38)));
        LinearLayout.LayoutParams recipientParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recipientParams.setMargins(0, 0, 0, dp(16));
        root.addView(recipient, recipientParams);
        addComposer(address);
        animateScreenIn(1);
    }

    private void showInboxMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("胶囊短信").setEnabled(false);
        menu.getMenu().add("回收站");
        menu.getMenu().add("应用图标");
        menu.getMenu().add("显示与文字");
        menu.getMenu().add("收件箱自动更新").setEnabled(false);
        boolean isDefaultSms = isDefaultSmsApp();
        android.view.MenuItem defaultSmsItem = menu.getMenu().add(
                isDefaultSms ? "默认短信应用：已启用" : "默认短信应用：未启用 · 点按设置");
        defaultSmsItem.setEnabled(!isDefaultSms);
        menu.setOnMenuItemClickListener(item -> {
            if (item == defaultSmsItem) {
                ensureSmsRole();
                return true;
            }
            if ("回收站".contentEquals(item.getTitle())) {
                showTrash();
                return true;
            }
            if ("应用图标".contentEquals(item.getTitle())) {
                showLauncherIconChooser();
                return true;
            }
            if ("显示与文字".contentEquals(item.getTitle())) {
                startActivity(new Intent(this, TypographySettingsActivity.class));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showLauncherIconChooser() {
        LauncherIconManager.Style selected = LauncherIconManager.selected(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(12), dp(4), dp(12), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("应用图标")
                .setView(choices)
                .setNegativeButton("取消", null)
                .create();
        for (LauncherIconManager.Style style : LauncherIconManager.Style.values()) {
            choices.addView(launcherIconChoice(style, selected, dialog),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));
        }
        dialog.show();
    }

    private View launcherIconChoice(
            LauncherIconManager.Style style,
            LauncherIconManager.Style selected,
            AlertDialog dialog) {
        boolean checked = style == selected;
        LinearLayout choice = row();
        choice.setPadding(dp(10), dp(8), dp(8), dp(8));
        choice.setBackground(rounded(checked
                ? palette.surfaceSelected : Color.TRANSPARENT, 18));

        ImageView preview = new ImageView(this);
        preview.setImageResource(style.previewResource);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(null);
        if (checked) preview.setOnClickListener(v -> handleHiddenSettingsTap());
        choice.addView(preview, new LinearLayout.LayoutParams(dp(62), dp(62)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(12), 0, dp(4), 0);
        TextView title = text(style.title, 16, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView description = text(style.description, 13, palette.textMuted);
        copy.addView(title);
        copy.addView(description);
        choice.addView(copy, new LinearLayout.LayoutParams(0, dp(64), 1));

        RadioButton radio = new RadioButton(this);
        radio.setChecked(checked);
        radio.setClickable(false);
        radio.setFocusable(false);
        choice.addView(radio, new LinearLayout.LayoutParams(dp(44), dp(62)));

        choice.setContentDescription(style.title + "，" + style.description
                + (checked ? "，当前使用" : ""));
        choice.setOnClickListener(v -> {
            if (!checked) {
                LauncherIconManager.select(this, style);
                Toast.makeText(this, "已切换为“" + style.title
                        + "”，桌面可能需要几秒刷新", Toast.LENGTH_LONG).show();
            }
            dialog.dismiss();
        });
        return choice;
    }

    private void handleHiddenSettingsTap() {
        long now = System.currentTimeMillis();
        if (now - hiddenSettingsLastTapAt > 1_500L) hiddenSettingsTapCount = 0;
        hiddenSettingsLastTapAt = now;
        hiddenSettingsTapCount++;
        if (hiddenSettingsTapCount < 3) return;
        hiddenSettingsTapCount = 0;
        startActivity(new Intent(this, AdvancedSettingsActivity.class));
    }

    private void stashInboxViews() {
        if (currentAddress != null || root.getChildCount() == 0 || !cachedInboxViews.isEmpty()) return;
        captureInboxScrollPosition();
        while (root.getChildCount() > 0) {
            View child = root.getChildAt(0);
            root.removeViewAt(0);
            cachedInboxViews.add(child);
        }
    }

    private void captureInboxScrollPosition() {
        if (inboxList == null || inboxList.getCount() == 0) return;
        int position = inboxList.getFirstVisiblePosition();
        Object item = inboxList.getAdapter().getItem(position);
        View firstVisible = inboxList.getChildAt(0);
        if (!(item instanceof SmsModels.Conversation) || firstVisible == null) return;
        inboxScrollAnchorAddress = ((SmsModels.Conversation) item).address();
        inboxScrollAnchorOffset = firstVisible.getTop() - inboxList.getPaddingTop();
    }

    private void restoreInboxScrollPosition(
            ListView list, List<SmsModels.Conversation> conversations) {
        if (inboxScrollAnchorAddress == null) return;
        for (int i = 0; i < conversations.size(); i++) {
            if (!inboxScrollAnchorAddress.equals(conversations.get(i).address())) continue;
            int position = i;
            int offset = inboxScrollAnchorOffset;
            list.post(() -> list.setSelectionFromTop(position, offset));
            return;
        }
    }

    private void prefetchRecentMessages(List<SmsModels.Conversation> conversations) {
        int count = Math.min(12, conversations.size());
        for (int i = 0; i < count; i++) {
            String address = conversations.get(i).address();
            if (messageCache.containsKey(address)) continue;
            io.execute(() -> messageCache.put(address,
                    new SmsRepository(this).recentMessages(address, 40)));
        }
    }

    private void renderMessages(
            LinearLayout container, ScrollView scroll, List<SmsModels.Message> values) {
        container.removeAllViews();
        int animatedFrom = Math.max(0, values.size() - 10);
        for (int i = 0; i < values.size(); i++) {
            SmsModels.Message message = values.get(i);
            View bubble = messageBubble(message);
            container.addView(bubble);
            if (i >= animatedFrom) animateMessageBubble(
                    bubble, message.isOutgoing(), (i - animatedFrom) * 18L);
        }
        scroll.post(this::scrollConversationToBottom);
    }

    private void showConversation(String address) {
        if (address == null || address.isBlank()) {
            showComposer();
            return;
        }
        stashInboxViews();
        showingTrash = false;
        selectedMessageIds.clear();
        messageRows.clear();
        selectConversationSubscription(address);
        currentRemoteOrigin = checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED
                ? new SmsRepository(this).latestCloudOrigin(address) : null;
        currentAddress = address;
        activeComposerBody = null;
        root.removeAllViews();
        addConversationHeader(address);
        FrameLayout scrollFrame = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        conversationScroll = scroll;
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, dp(CONVERSATION_SCROLLBAR_GUTTER_DP), 0);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout messages = new LinearLayout(this);
        conversationMessages = messages;
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, dp(12), 0, dp(18));
        scroll.addView(messages);
        scrollFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrollbarThumb = new View(this);
        scrollbarThumb.setBackground(rounded(palette.scrollThumb, 2));
        scrollbarThumb.setElevation(dp(12));
        scrollbarThumb.setAlpha(0f);
        scrollbarThumb.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams scrollbarParams = new FrameLayout.LayoutParams(
                dp(2), dp(44), Gravity.END | Gravity.TOP);
        scrollbarParams.setMargins(0, 0, dp(2), 0);
        scrollFrame.addView(scrollbarThumb, scrollbarParams);
        Runnable fadeScrollbar = () -> scrollbarThumb.animate().alpha(0f)
                .setDuration(250L)
                .withEndAction(() -> scrollbarThumb.setVisibility(View.INVISIBLE))
                .start();
        scroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            scrollbarThumb.removeCallbacks(fadeScrollbar);
            scrollbarThumb.animate().cancel();
            if (!updateConversationScrollbar(scrollbarThumb, scroll)) return;
            scrollbarThumb.setVisibility(View.VISIBLE);
            scrollbarThumb.setAlpha(1f);
            scrollbarThumb.postDelayed(fadeScrollbar, 650L);
        });
        // Let messages use the physical screen width while keeping the header and composer aligned
        // to the normal root insets. The right padding remains a dedicated scrollbar safety zone.
        LinearLayout.LayoutParams scrollFrameParams = new LinearLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels, 0, 1);
        scrollFrameParams.setMargins(-root.getPaddingLeft(), 0, 0, 0);
        root.addView(scrollFrame, scrollFrameParams);
        addComposer(null);
        animateScreenIn(1);

        List<SmsModels.Message> cachedMessages = messageCache.get(address);
        if (cachedMessages != null) renderMessages(messages, scroll, cachedMessages);
        io.execute(() -> {
            SmsRepository repository = new SmsRepository(this);
            repository.markRead(address);
            if (cachedMessages != null) return;
            List<SmsModels.Message> values = repository.recentMessages(address, 40);
            messageCache.put(address, values);
            runOnUiThread(() -> {
                if (address.equals(currentAddress)) renderMessages(messages, scroll, values);
            });
        });
    }

    private boolean updateConversationScrollbar(View thumb, ScrollView scroll) {
        View content = scroll.getChildAt(0);
        int trackHeight = scroll.getHeight();
        int contentHeight = content == null ? 0 : content.getHeight();
        if (trackHeight <= 0 || contentHeight <= trackHeight) {
            thumb.setVisibility(View.INVISIBLE);
            return false;
        }
        int thumbHeight = Math.max(dp(44), Math.round(
                trackHeight * (trackHeight / (float) contentHeight)));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        if (params.height != thumbHeight) {
            params.height = thumbHeight;
            thumb.setLayoutParams(params);
        }
        float progress = scroll.getScrollY()
                / (float) Math.max(1, contentHeight - trackHeight);
        thumb.setTranslationY(Math.round((trackHeight - thumbHeight) * progress));
        thumb.bringToFront();
        return true;
    }

    private void selectConversationSubscription(String address) {
        if (inboxConversations != null) {
            for (SmsModels.Conversation conversation : inboxConversations) {
                if (conversation.address().equals(address)
                        && conversation.subscriptionId()
                                != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    selectedSubscriptionId = conversation.subscriptionId();
                    return;
                }
            }
        }
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            int latest = new SmsRepository(this).latestSubscriptionId(address);
            if (latest != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                selectedSubscriptionId = latest;
            }
        }
    }

    private void addConversationHeader(String titleText) {
        LinearLayout header = row();
        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_revert);
        back.setColorFilter(palette.textPrimary);
        back.setBackground(rounded(palette.surfaceMuted, 22));
        back.setContentDescription("返回");
        back.setOnClickListener(v -> {
            if (messageSelectionMode) clearMessageSelection();
            else { currentAddress = null; showInbox(); }
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        backParams.setMargins(0, dp(6), 0, dp(6));
        header.addView(back, backParams);
        TextView select = text("☐", 24, palette.textPrimary);
        conversationSelect = select;
        select.setGravity(Gravity.CENTER);
        select.setBackground(rounded(palette.surfaceMuted, 22));
        select.setContentDescription("选择短信");
        select.setOnClickListener(v -> {
            messageSelectionMode = !messageSelectionMode;
            if (!messageSelectionMode) selectedMessageIds.clear();
            refreshMessageSelectionUi();
            pulse(v);
        });
        LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        selectParams.setMargins(dp(8), dp(6), 0, dp(6));
        header.addView(select, selectParams);
        TextView title = conversationText(titleText, 20, palette.textPrimary);
        conversationTitle = title;
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(14), 0, 0, 0);
        title.setMinHeight(dp(56));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton delete = new ImageButton(this);
        conversationDelete = delete;
        delete.setImageResource(android.R.drawable.ic_menu_delete);
        delete.setColorFilter(palette.danger);
        delete.setBackground(rounded(palette.dangerSurface, 22));
        delete.setContentDescription("删除所选短信");
        delete.setVisibility(View.GONE);
        delete.setOnClickListener(v -> confirmTrashSelectedMessages());
        header.addView(delete, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);
    }

    private void addComposer(EditText addressInput) {
        boolean remoteReply = addressInput == null && currentRemoteOrigin != null;
        CloudMessageOriginStore.Origin remoteRoute = currentRemoteOrigin;
        if (!remoteReply) refreshSimChoices();
        LinearLayout simSelector = row();
        simSelector.setGravity(Gravity.CENTER_VERTICAL);
        simSelector.setPadding(0, 0, 0, dp(8));
        TextView simLabel = text("发送号码", 13, palette.textSecondary);
        simLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        simSelector.addView(simLabel, new LinearLayout.LayoutParams(dp(72), dp(44)));
        if (remoteReply) {
            TextView route = text("通过 " + remoteRoute.displayLabel(), 14, Color.WHITE);
            route.setGravity(Gravity.CENTER);
            route.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            route.setBackground(rounded(palette.violet, 16));
            route.setContentDescription("远端发送线路，" + remoteRoute.displayLabel());
            LinearLayout.LayoutParams routeParams = new LinearLayout.LayoutParams(0, dp(50), 1);
            routeParams.setMargins(0, 0, dp(8), 0);
            simSelector.addView(route, routeParams);
        }
        for (SimChoice choice : remoteReply ? List.<SimChoice>of() : simChoices) {
            TextView card = simChoiceView(choice);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    0, dp(50), 1);
            cardParams.setMargins(0, 0, dp(8), 0);
            simSelector.addView(card, cardParams);
            card.setOnClickListener(v -> {
                selectedSubscriptionId = choice.subscriptionId;
                for (int i = 1; i < simSelector.getChildCount(); i++) {
                    View child = simSelector.getChildAt(i);
                    if (child instanceof TextView) styleSimChoice(
                            (TextView) child, (SimChoice) child.getTag());
                }
                pulse(card);
            });
        }
        if (remoteReply || !simChoices.isEmpty()) root.addView(simSelector);

        LinearLayout composer = row();
        composer.setPadding(0, dp(8), 0, dp(4));
        EditText body = new EditText(this);
        activeComposerBody = body;
        body.setHint("输入短信…");
        body.setMinHeight(dp(48));
        body.setMaxLines(5);
        setConversationTextSize(body, 16);
        body.setLineSpacing(0, typography.conversationLineSpacingMultiplier());
        body.setPadding(dp(18), dp(10), dp(18), dp(10));
        body.setTextColor(palette.textPrimary);
        body.setHintTextColor(palette.textMuted);
        body.setBackground(rounded(palette.surfaceMuted, 24));
        body.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    && !body.hasFocus()) body.requestFocus();
            return false;
        });
        body.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) return;
            body.postDelayed(() -> {
                if (body.hasFocus()) return;
                WindowInsets current = root.getRootWindowInsets();
                if (current == null || !current.isVisible(WindowInsets.Type.ime())) return;
                android.view.WindowInsetsController controller = root.getWindowInsetsController();
                if (controller != null) controller.hide(WindowInsets.Type.ime());
            }, 180L);
        });
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        bodyParams.setMargins(0, 0, dp(10), 0);
        composer.addView(body, bodyParams);
        ImageButton send = new ImageButton(this);
        send.setImageResource(android.R.drawable.ic_menu_send);
        send.setColorFilter(Color.WHITE);
        send.setBackground(rounded(palette.accent, 24));
        send.setContentDescription("发送");
        send.setOnClickListener(v -> {
            pulse(send);
            String address = addressInput == null ? currentAddress : addressInput.getText().toString().trim();
            String message = body.getText().toString().trim();
            if (address == null || address.isBlank() || message.isBlank()) {
                Toast.makeText(this, "请填写收件人和短信内容", Toast.LENGTH_SHORT).show();
                return;
            }
            send.setEnabled(false);
            if (remoteReply) {
                CloudSyncEngine.sendRemoteAsync(this, remoteRoute, address, message,
                        (success, error) -> runOnUiThread(() -> {
                            send.setEnabled(true);
                            if (error != null || !Boolean.TRUE.equals(success)) {
                                Toast.makeText(this, error == null ? "远端发送失败"
                                        : error.getMessage(), Toast.LENGTH_LONG).show();
                                return;
                            }
                            body.setText("");
                            Toast.makeText(this, "已交给 " + remoteRoute.displayLabel()
                                    + " 发送", Toast.LENGTH_SHORT).show();
                            CloudSyncScheduler.scheduleImmediate(this);
                        }));
                return;
            }
            SmsSender.send(this, address, message, selectedSubscriptionId,
                    (success, result) -> runOnUiThread(() -> {
                send.setEnabled(true);
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
                if (success) {
                    body.setText("");
                    messageCache.remove(address);
                    inboxConversations = null;
                    showConversation(address);
                }
            }));
        });
        composer.addView(send, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(composer);
    }

    private void scrollConversationToBottom() {
        ScrollView scroll = conversationScroll;
        if (scroll == null || scroll.getChildCount() == 0) return;
        View content = scroll.getChildAt(0);
        int bottom = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.scrollTo(0, bottom);
    }

    private View messageBubble(SmsModels.Message message) {
        LinearLayout line = row();
        messageRows.put(message.id(), line);
        line.setGravity(message.isOutgoing() ? Gravity.END : Gravity.START);
        line.setPadding(dp(MESSAGE_EDGE_MARGIN_DP), dp(2),
                dp(MESSAGE_EDGE_MARGIN_DP), dp(2));
        line.setOnClickListener(v -> {
            if (!messageSelectionMode) return;
            if (!selectedMessageIds.add(message.id())) selectedMessageIds.remove(message.id());
            refreshMessageSelectionUi();
        });
        TextView bubble = conversationText(message.body(), 16,
                message.isOutgoing() ? Color.WHITE : palette.textPrimary);
        VerificationAnnotationStore.Annotation verification =
                message.isOutgoing() ? null
                        : verificationAnnotations.resolve(message.id(), message.body());
        bubble.setTextIsSelectable(true);
        decorateMessageText(bubble, message, verification);
        bubble.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels
                * MESSAGE_MAX_WIDTH_FRACTION));
        bubble.setLineSpacing(0, typography.conversationLineSpacingMultiplier());
        bubble.setPadding(dp(16), dp(11), dp(16), dp(11));
        bubble.setBackground(rounded(message.isOutgoing()
                ? palette.accent : palette.messageIncoming, 19));
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(message.isOutgoing() ? Gravity.END : Gravity.START);
        stack.addView(bubble);
        if (message.origin() != null) {
            TextView origin = conversationText((message.isOutgoing() ? "通过 " : "来自 ")
                            + message.origin().displayLabel(), 11,
                    palette.textMuted);
            origin.setPadding(dp(5), dp(2), dp(5), 0);
            stack.addView(origin);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int bubbleMargin = typography.bubbleVerticalMarginDp();
        params.setMargins(0, dp(bubbleMargin), 0, dp(bubbleMargin));
        line.addView(stack, params);
        styleMessageRow(line, message.id());
        return line;
    }

    private boolean sameOrigin(
            CloudMessageOriginStore.Origin left, CloudMessageOriginStore.Origin right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return java.util.Objects.equals(left.deviceId, right.deviceId)
                && java.util.Objects.equals(left.deviceName, right.deviceName)
                && left.subscriptionId == right.subscriptionId
                && java.util.Objects.equals(left.lineNumber, right.lineNumber)
                && left.simSlot == right.simSlot;
    }

    private void decorateMessageText(TextView bubble, SmsModels.Message message,
            VerificationAnnotationStore.Annotation verification) {
        String body = message.body();
        SpannableString styled = new SpannableString(body);
        boolean hasClickableSpan = false;
        if (verification != null) {
            String code = verification.code();
            int start = body.indexOf(code);
            if (start >= 0) {
                VerificationCodeSpan codeSpan = new VerificationCodeSpan();
                styled.setSpan(codeSpan, start, start + code.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ClickableSpan() {
                    @Override public void onClick(View widget) {
                        if (messageSelectionMode) {
                            toggleMessageSelection(message.id());
                            return;
                        }
                        copyInlineVerificationCode(code, bubble, codeSpan);
                    }

                    @Override public void updateDrawState(TextPaint paint) {
                        paint.setUnderlineText(false);
                    }
                }, start, start + code.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                hasClickableSpan = true;
            }
        }

        Matcher matcher = Patterns.WEB_URL.matcher(body);
        while (matcher.find()) {
            int start = matcher.start();
            int end = trimUrlEnd(body, start, matcher.end());
            if (end <= start) continue;
            String url = body.substring(start, end);
            styled.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    if (messageSelectionMode) {
                        toggleMessageSelection(message.id());
                        return;
                    }
                    showLinkMenu(bubble, url);
                }

                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(palette.accentText);
                    paint.setUnderlineText(true);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            hasClickableSpan = true;
        }

        bubble.setText(styled);
        if (hasClickableSpan) {
            bubble.setMovementMethod(LinkMovementMethod.getInstance());
            bubble.setHighlightColor(Color.argb(90, 110, 168, 255));
            bubble.setLinksClickable(true);
        }
    }

    private int trimUrlEnd(String body, int start, int end) {
        int validEnd = start;
        while (validEnd < end && isUrlCharacter(body.charAt(validEnd))) {
            validEnd++;
        }
        end = validEnd;
        while (end > start) {
            char last = body.charAt(end - 1);
            if (".:?)]}\"'".indexOf(last) < 0) break;
            end--;
        }
        return end;
    }

    private boolean isUrlCharacter(char value) {
        if (value > 0x7f || Character.isWhitespace(value)) return false;
        return Character.isLetterOrDigit(value)
                || ":/?#[]@.$&'()*+-_=~%".indexOf(value) >= 0;
    }

    private void showLinkMenu(TextView anchor, String rawUrl) {
        String url = rawUrl.matches("(?i)^[a-z][a-z0-9+.-]*://.*")
                ? rawUrl : "https://" + rawUrl;
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add("浏览器打开");
        menu.getMenu().add("复制链接");
        menu.setOnMenuItemClickListener(item -> {
            if ("浏览器打开".contentEquals(item.getTitle())) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (RuntimeException error) {
                    Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
                }
            } else {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                clipboard.setPrimaryClip(ClipData.newPlainText("链接", rawUrl));
                Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        menu.show();
    }

    private void toggleMessageSelection(long messageId) {
        if (!selectedMessageIds.add(messageId)) selectedMessageIds.remove(messageId);
        refreshMessageSelectionUi();
    }

    private final class VerificationCodeSpan extends ReplacementSpan {
        private float scale = 1f;

        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt metrics) {
            Paint codePaint = codePaint(paint);
            return Math.round(codePaint.measureText(text, start, end));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            Paint codePaint = codePaint(paint);
            float width = codePaint.measureText(text, start, end);
            Paint.FontMetrics metrics = codePaint.getFontMetrics();
            float centerY = y + (metrics.ascent + metrics.descent) / 2f;
            float padding = codePaint.getTextSize() * 0.10f;
            Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
            background.setColor(palette.verificationSurface);

            canvas.save();
            canvas.scale(scale, scale, x + width / 2f, centerY);
            canvas.drawRoundRect(x - padding, y + metrics.ascent - padding / 2f,
                    x + width + padding, y + metrics.descent + padding / 2f,
                    padding, padding, background);
            canvas.drawText(text, start, end, x, y, codePaint);
            canvas.restore();
        }

        private Paint codePaint(Paint source) {
            Paint result = new Paint(source);
            result.setColor(palette.verificationText);
            result.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            return result;
        }
    }

    private void springCode(TextView bubble, VerificationCodeSpan codeSpan) {
        if (!motionEnabled()) return;
        ValueAnimator spring = ValueAnimator.ofFloat(1f, 0.84f, 1.09f, 0.97f, 1f);
        spring.setDuration(260L);
        spring.addUpdateListener(animation -> {
            codeSpan.scale = (float) animation.getAnimatedValue();
            bubble.invalidate();
        });
        spring.start();
    }

    private void refreshMessageSelectionUi() {
        if (conversationTitle != null) {
            conversationTitle.setText(messageSelectionMode
                    ? "已选择 " + selectedMessageIds.size() + " 条" : currentAddress);
        }
        if (conversationSelect != null) {
            conversationSelect.setText(messageSelectionMode ? "☑" : "☐");
            conversationSelect.setTextColor(messageSelectionMode
                    ? palette.accentText : palette.textPrimary);
            conversationSelect.setContentDescription(messageSelectionMode ? "退出选择" : "选择短信");
        }
        if (conversationDelete != null) {
            conversationDelete.setVisibility(selectedMessageIds.isEmpty() ? View.GONE : View.VISIBLE);
            if (!selectedMessageIds.isEmpty()) pulse(conversationDelete);
        }
        for (Map.Entry<Long, View> entry : messageRows.entrySet()) {
            styleMessageRow(entry.getValue(), entry.getKey());
        }
    }

    private void styleMessageRow(View row, long messageId) {
        boolean selected = selectedMessageIds.contains(messageId);
        row.setBackground(selected
                ? rounded(palette.surfaceSelected, 18)
                : rounded(Color.TRANSPARENT, 18));
        row.setContentDescription(selected ? "已选择的短信"
                : messageSelectionMode ? "短信，点按可选择" : "短信，长按可选择文字");
    }

    private void clearMessageSelection() {
        messageSelectionMode = false;
        selectedMessageIds.clear();
        refreshMessageSelectionUi();
    }

    private void confirmTrashSelectedMessages() {
        int count = selectedMessageIds.size();
        if (count == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("移到回收站？")
                .setMessage("已选择 " + count + " 条短信。删除后仍可在回收站恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移到回收站", (dialog, which) -> moveSelectedMessagesToTrash())
                .show();
    }

    private void moveSelectedMessagesToTrash() {
        Set<Long> ids = new LinkedHashSet<>(selectedMessageIds);
        String address = currentAddress;
        if (conversationDelete != null) conversationDelete.setEnabled(false);
        io.execute(() -> {
            SmsRepository repository = new SmsRepository(this);
            int moved = repository.moveToTrash(this, ids);
            List<SmsModels.Message> remaining = repository.recentMessages(address, 40);
            messageCache.put(address, remaining);
            inboxConversations = null;
            cachedInboxViews.clear();
            runOnUiThread(() -> {
                selectedMessageIds.clear();
                Toast.makeText(this, moved == ids.size()
                        ? "已移到回收站" : "已移动 " + moved + " 条，部分短信删除失败",
                        Toast.LENGTH_SHORT).show();
                if (remaining.isEmpty()) showInbox(); else showConversation(address);
            });
        });
    }

    private void confirmTrashConversation(SmsModels.Conversation conversation) {
        String title = displayName(conversation.address());
        new AlertDialog.Builder(this)
                .setTitle("删除与“" + title + "”的会话？")
                .setMessage("这段会话中的全部短信都会移到回收站，之后仍可逐条恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移到回收站", (dialog, which) -> {
                    io.execute(() -> {
                        int moved = new SmsRepository(this)
                                .moveConversationToTrash(this, conversation.address());
                        messageCache.remove(conversation.address());
                        inboxConversations = null;
                        cachedInboxViews.clear();
                        runOnUiThread(() -> {
                            Toast.makeText(this, moved > 0
                                    ? "会话已移到回收站" : "删除失败",
                                    Toast.LENGTH_SHORT).show();
                            showInbox();
                        });
                    });
                })
                .show();
    }

    private void showTrash() {
        if (!showingTrash) stashInboxViews();
        showingTrash = true;
        currentAddress = null;
        inboxFrame = null;
        root.removeAllViews();
        TrashStore store = new TrashStore(this);
        store.purgeExpired();
        List<TrashStore.Entry> entries = store.all();

        LinearLayout header = row();
        header.setPadding(0, dp(6), 0, dp(8));
        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_revert);
        back.setColorFilter(palette.textPrimary);
        back.setBackground(rounded(palette.surfaceMuted, 22));
        back.setContentDescription("返回收件箱");
        back.setOnClickListener(v -> showInbox());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("回收站", 21, palette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(14), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        ImageButton settings = new ImageButton(this);
        settings.setImageResource(android.R.drawable.ic_menu_preferences);
        settings.setColorFilter(palette.textPrimary);
        settings.setBackground(rounded(palette.surfaceMuted, 22));
        settings.setContentDescription("回收站设置");
        settings.setOnClickListener(v -> showTrashSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        int retention = store.retentionDays();
        TextView summary = text(retention == 0 ? "短信将一直保留，直到手动永久删除"
                : "已删除短信保留 " + retention + " 天，过期后自动清理",
                13, palette.textMuted);
        summary.setPadding(dp(4), 0, 0, dp(10));
        root.addView(summary);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public TrashStore.Entry getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return trashRow(getItem(position));
            }
        });
        TextView empty = text("回收站是空的\n删除的短信会暂存在这里", 17,
                palette.textMuted);
        empty.setGravity(Gravity.CENTER);
        FrameLayout content = new FrameLayout(this);
        content.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        content.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, matchMatch());
        animateScreenIn(1);
    }

    private View trashRow(TrashStore.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setBackground(rounded(palette.surfaceMuted, 18));
        TextView address = text((entry.isOutgoing() ? "发送给 " : "来自 ")
                + displayName(entry.address()), 16, palette.textPrimary);
        address.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView body = text(entry.body(), 15, palette.textSecondary);
        body.setMaxLines(3);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.setPadding(0, dp(5), 0, dp(7));
        String metaText = DateFormat.format("M月d日 HH:mm", entry.date()).toString()
                + "  ·  点击管理";
        Integer slot = subscriptionSlots.get(entry.subscriptionId());
        if (slot != null) metaText += "  ·  SIM " + slot;
        TextView meta = text(metaText, 12, palette.textMuted);
        row.addView(address);
        row.addView(body);
        row.addView(meta);
        row.setContentDescription("回收站短信，点击可恢复或永久删除");
        row.setOnClickListener(v -> showTrashEntryOptions(entry));
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(0, dp(5), 0, dp(5));
        wrapper.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private void showTrashEntryOptions(TrashStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(entry.address())
                .setItems(new String[]{"恢复短信", "永久删除"}, (dialog, which) -> {
                    if (which == 0) restoreTrashEntry(entry);
                    else confirmPermanentlyDelete(entry);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreTrashEntry(TrashStore.Entry entry) {
        io.execute(() -> {
            boolean restored = new TrashStore(this).restore(entry.trashId());
            messageCache.remove(entry.address());
            inboxConversations = null;
            cachedInboxViews.clear();
            runOnUiThread(() -> {
                Toast.makeText(this, restored ? "短信已恢复" : "恢复失败",
                        Toast.LENGTH_SHORT).show();
                showTrash();
            });
        });
    }

    private void confirmPermanentlyDelete(TrashStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("永久删除这条短信？")
                .setMessage("永久删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("永久删除", (dialog, which) -> {
                    new TrashStore(this).remove(entry.trashId());
                    showTrash();
                })
                .show();
    }

    private void showTrashSettings() {
        int[] days = {7, 30, 90, 0};
        String[] labels = {"7 天", "30 天（默认）", "90 天", "永久保留"};
        int current = new TrashStore(this).retentionDays();
        int selected = 1;
        for (int i = 0; i < days.length; i++) if (days[i] == current) selected = i;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("短信保留时间")
                .setSingleChoiceItems(labels, selected, null)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int position = dialog.getListView().getCheckedItemPosition();
                    new TrashStore(this).setRetentionDays(days[position]);
                    dialog.dismiss();
                    showTrash();
                }));
        dialog.show();
    }

    private boolean motionEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    private void animateScreenIn(int direction) {
        root.animate().cancel();
        if (!motionEnabled()) {
            root.setAlpha(1f);
            root.setTranslationX(0f);
            return;
        }
        root.setAlpha(0f);
        root.setTranslationX(dp(14) * direction);
        root.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(190L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    private void animateConversationRow(View row, int position) {
        if (!motionEnabled()) return;
        row.setAlpha(0f);
        row.setTranslationY(dp(9));
        row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.min(position, 9) * 22L)
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    private void animateMessageBubble(View bubble, boolean outgoing, long delay) {
        if (!motionEnabled()) return;
        bubble.setAlpha(0f);
        bubble.setTranslationX(dp(outgoing ? 12 : -12));
        bubble.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(delay)
                .setDuration(170L)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .start();
    }

    private void pulse(View view) {
        if (!motionEnabled()) return;
        view.animate().cancel();
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(65L)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(150L)
                        .setInterpolator(new OvershootInterpolator(1.1f))
                        .start())
                .start();
    }

    private boolean isDefaultSmsApp() {
        RoleManager roles = getSystemService(RoleManager.class);
        return roles.isRoleAvailable(RoleManager.ROLE_SMS) && roles.isRoleHeld(RoleManager.ROLE_SMS);
    }

    private String inboxTime(long timestamp) {
        long now = System.currentTimeMillis();
        if (DateUtils.isToday(timestamp)) return DateFormat.format("h:mm a", timestamp).toString();
        if (now - timestamp < DateUtils.WEEK_IN_MILLIS) {
            return DateFormat.format("EEE", timestamp).toString();
        }
        return DateFormat.format("M/d", timestamp).toString();
    }

    private void refreshInboxFromProvider() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        int generation = ++providerRefreshGeneration;
        String openAddress = currentAddress;
        io.execute(() -> {
            SmsRepository repository = new SmsRepository(this);
            List<SmsModels.Conversation> conversations = repository.conversations();
            List<SmsModels.Message> openMessages = null;
            if (openAddress != null && !openAddress.isBlank()) {
                repository.markRead(openAddress);
                openMessages = repository.recentMessages(openAddress, 40);
            }
            saveInboxSnapshot(conversations);
            List<SmsModels.Message> refreshedOpenMessages = openMessages;
            runOnUiThread(() -> {
                if (generation != providerRefreshGeneration) return;
                boolean changed = !sameConversations(inboxConversations, conversations);
                inboxConversations = conversations;
                cachedInboxViews.clear();
                if (refreshedOpenMessages != null && openAddress.equals(currentAddress)
                        && conversationMessages != null && conversationScroll != null) {
                    messageCache.put(openAddress, refreshedOpenMessages);
                    renderMessages(conversationMessages, conversationScroll, refreshedOpenMessages);
                    return;
                }
                if (changed && currentAddress == null && inboxFrame != null) {
                    refreshSubscriptionSlots();
                    renderConversationFrame(conversations);
                }
                prefetchRecentMessages(conversations);
            });
        });
    }

    private void openContactPicker() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSIONS);
            return;
        }
        Intent pick = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        startActivityForResult(pick, REQUEST_CONTACT);
    }

    private void fillRecipientFromContact(Uri uri) {
        if (uri == null || composerAddressInput == null) return;
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        };
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String number = cursor.getString(0);
                String name = cursor.getString(1);
                composerAddressInput.setText(number);
                composerAddressInput.setSelection(number == null ? 0 : number.length());
                if (name != null && !name.isBlank()) composerAddressInput.setContentDescription(
                        "收件人 " + name + " " + number);
            }
        } catch (SecurityException ignored) {
            Toast.makeText(this, "需要通讯录权限", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView simChoiceView(SimChoice choice) {
        TextView card = text("SIM " + choice.slot + "\n" + choice.number, 12,
                palette.textSecondary);
        card.setGravity(Gravity.CENTER);
        card.setTag(choice);
        styleSimChoice(card, choice);
        return card;
    }

    private void styleSimChoice(TextView card, SimChoice choice) {
        boolean selected = choice.subscriptionId == selectedSubscriptionId;
        card.setTextColor(selected ? Color.WHITE : palette.textSecondary);
        card.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        card.setBackground(rounded(selected
                ? (choice.slot == 1 ? palette.accent : palette.violet)
                : palette.surfaceMuted, 16));
        card.setContentDescription("SIM " + choice.slot + "，" + choice.number
                + (selected ? "，已选择" : ""));
    }

    private void refreshSimChoices() {
        subscriptionSlots.clear();
        simChoices.clear();
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            SubscriptionManager manager = getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> active = manager.getActiveSubscriptionInfoList();
            if (active == null) return;
            boolean selectedStillActive = false;
            for (SubscriptionInfo info : active) {
                int slot = info.getSimSlotIndex();
                if (slot < 0) continue;
                int subscriptionId = info.getSubscriptionId();
                subscriptionSlots.put(subscriptionId, slot + 1);
                String number = "号码未写入 SIM";
                if (checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)
                        == PackageManager.PERMISSION_GRANTED) {
                    String provided = manager.getPhoneNumber(subscriptionId);
                    if (provided != null && !provided.isBlank()) number = provided;
                }
                simChoices.add(new SimChoice(subscriptionId, slot + 1, number));
                if (subscriptionId == selectedSubscriptionId) selectedStillActive = true;
            }
            simChoices.sort((left, right) -> Integer.compare(left.slot, right.slot));
            if (!selectedStillActive && !simChoices.isEmpty()) {
                int systemDefault = SubscriptionManager.getDefaultSmsSubscriptionId();
                selectedSubscriptionId = simChoices.get(0).subscriptionId;
                for (SimChoice choice : simChoices) {
                    if (choice.subscriptionId == systemDefault) selectedSubscriptionId = systemDefault;
                }
            }
        } catch (SecurityException ignored) {}
    }

    private void refreshSubscriptionSlots() {
        refreshSimChoices();
    }

    private Map<String, ContactIdentity> loadContactIdentities(
            List<SmsModels.Conversation> conversations) {
        Map<String, ContactIdentity> result = new HashMap<>();
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return result;
        }
        for (SmsModels.Conversation conversation : conversations) {
            String address = conversation.address();
            if (!looksLikePhoneNumber(address)) continue;
            Uri lookup = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
            String[] projection = {
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
            };
            try (android.database.Cursor cursor = getContentResolver().query(
                    lookup, projection, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) continue;
                String name = cursor.getString(0);
                String photoUri = cursor.getString(1);
                Bitmap photo = loadContactPhoto(photoUri);
                result.put(address, new ContactIdentity(name, photo));
            } catch (SecurityException ignored) {
                return new HashMap<>();
            }
        }
        return result;
    }

    private Bitmap loadContactPhoto(String photoUri) {
        if (photoUri == null || photoUri.isBlank()) return null;
        try (InputStream stream = getContentResolver().openInputStream(Uri.parse(photoUri))) {
            return stream == null ? null : BitmapFactory.decodeStream(stream);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean looksLikePhoneNumber(String address) {
        if (address == null || address.isBlank()) return false;
        int digits = 0;
        for (int i = 0; i < address.length(); i++) {
            char value = address.charAt(i);
            if (Character.isDigit(value)) digits++;
            else if (value != '+' && value != ' ' && value != '-' && value != '(' && value != ')') {
                return false;
            }
        }
        return digits >= 3;
    }

    private String displayName(String address) {
        ContactIdentity identity = contactIdentities.get(address);
        return identity == null || identity.name == null || identity.name.isBlank()
                ? address : identity.name;
    }

    private static final class ContactIdentity {
        final String name;
        final Bitmap photo;

        ContactIdentity(String name, Bitmap photo) {
            this.name = name;
            this.photo = photo;
        }
    }

    private static final class SimChoice {
        final int subscriptionId;
        final int slot;
        final String number;

        SimChoice(int subscriptionId, int slot, String number) {
            this.subscriptionId = subscriptionId;
            this.slot = slot;
            this.number = number;
        }
    }

    private int avatarColor(String key) {
        int hash = key == null ? 0 : key.hashCode();
        return palette.avatar(hash);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private TextView inboxText(String value, float baseSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        setInboxTextSize(view, baseSp);
        view.setTextColor(color);
        return view;
    }

    private TextView conversationText(String value, float baseSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        setConversationTextSize(view, baseSp);
        view.setTextColor(color);
        return view;
    }

    private void setInboxTextSize(TextView view, float baseSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, typography.inboxScaledSp(baseSp));
    }

    private void setConversationTextSize(TextView view, float baseSp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                typography.conversationScaledSp(baseSp));
    }

    private Button pillButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundColor(palette.accent);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams matchMatch() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    private String initial(String address) {
        return address == null || address.isBlank() ? "?" : address.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        io.shutdownNow();
        super.onDestroy();
    }
}
