package com.example.notifreader;

import java.util.ArrayList;
import java.util.List;

// Shared in-memory history shown by MainActivity
public class NotificationLog {
    public static final List<String> items = new ArrayList<>();
    public static Runnable onChange;

    public static void add(String s) {
        items.add(0, s);
        if (items.size() > 50) items.remove(items.size() - 1);
        if (onChange != null) onChange.run();
    }
}
