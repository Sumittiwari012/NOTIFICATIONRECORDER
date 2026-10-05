package com.example.notifreader;

import java.util.ArrayList;
import java.util.List;

// Shared in-memory list of received UPI payments shown by MainActivity
public class NotificationLog {

    // A received UPI payment
    public static class Entry {
        public final String source;  // UPI app it came from, e.g. "Google Pay"
        public final String name;    // payer's name (may be empty)
        public final String amount;  // e.g. "₹1.00"
        public final String note;    // transaction note starting with "GR" (may be empty)
        public final String time;    // date and time recorded

        public Entry(String source, String name, String amount, String note, String time) {
            this.source = source;
            this.name = name;
            this.amount = amount;
            this.note = note;
            this.time = time;
        }
    }

    public static final List<Entry> items = new ArrayList<>();
    public static Runnable onChange;

    public static void add(Entry e) {
        items.add(0, e);
        if (items.size() > 100) items.remove(items.size() - 1);
        if (onChange != null) onChange.run();
    }
}
