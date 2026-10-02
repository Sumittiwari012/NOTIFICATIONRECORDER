package com.example.notifreader;

import java.util.ArrayList;
import java.util.List;

// Shared in-memory records shown by MainActivity
public class NotificationLog {

    // A payment: has an amount
    public static class Entry {
        public final String source;  // app / sender the notification came from
        public final String name;    // account holder (may be empty)
        public final String amount;  // e.g. "₹1.00"
        public final String time;    // date and time recorded

        public Entry(String source, String name, String amount, String time) {
            this.source = source;
            this.name = name;
            this.amount = amount;
            this.time = time;
        }
    }

    // A normal message: no amount in it
    public static class Msg {
        public final String source;
        public final String message; // complete message text
        public final String time;

        public Msg(String source, String message, String time) {
            this.source = source;
            this.message = message;
            this.time = time;
        }
    }

    public static final List<Entry> items = new ArrayList<>();
    public static final List<Msg> messages = new ArrayList<>();
    public static Runnable onChange;

    public static void add(Entry e) {
        items.add(0, e);
        if (items.size() > 100) items.remove(items.size() - 1);
        if (onChange != null) onChange.run();
    }

    public static void addMessage(Msg m) {
        messages.add(0, m);
        if (messages.size() > 200) messages.remove(messages.size() - 1);
        if (onChange != null) onChange.run();
    }
}