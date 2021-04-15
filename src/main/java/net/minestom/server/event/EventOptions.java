package net.minestom.server.event;

/**
 * This class holds options information for a EventCallback
 * priority is a byte that represents the priority of the Event. The smaller the earlier an event is called.
 * The name can be used to specify that another event should be called before or after this one.
 * before is an array of strings with the names of the events that should be called before this one, if they are present
 * after is an array of strings with the names of the events that should be called after this one, if they are present
 */
public class EventOptions {

    private byte priority = 0;
    private String name = null;
    private String[] before = {};
    private String[] after = {};

    public byte getPriority() {
        return priority;
    }

    public String getName() {
        return name;
    }

    public String[] getBefore() {
        return before;
    }

    public boolean hasBefore(String beforeName) {
        return isInStringArray(before, beforeName);
    }

    public String[] getAfter() {
        return after;
    }

    public boolean hasAfter(String afterName) {
        return isInStringArray(after, afterName);
    }

    public EventOptions priority(byte priority) {
        this.priority = priority;
        return this;
    }

    public EventOptions name(String name) {
        this.name = name;
        return this;
    }

    public EventOptions before(String... before) {
        this.before = before;
        return this;
    }

    public EventOptions addBefore(String addedBefore) {
        before = addToStringArray(before, addedBefore);
        return this;
    }

    public EventOptions removeBefore(String removedBefore) {
        before = removeFromStringArray(before, removedBefore);
        return this;
    }

    public EventOptions after(String... after) {
        this.after = after;
        return this;
    }

    public EventOptions addAfter(String addedAfter) {
        after = addToStringArray(after, addedAfter);
        return this;
    }

    public EventOptions removeAfter(String removedAfter) {
        after = removeFromStringArray(after, removedAfter);
        return this;
    }

    // --- Array Stuff ---

    private boolean isInStringArray(String[] array, String string) {
        for (String other : array) {
            if(other.equals(string)) return true;
        }
        return false;
    }

    private String[] addToStringArray(String[] array, String addedString) {
        if(isInStringArray(array, addedString)) return array; //add unneeded
        String[] newArray = new String[array.length+1];
        System.arraycopy(array, 0, newArray, 0, array.length);
        newArray[newArray.length-1] = addedString;
        return newArray;
    }

    public String[] removeFromStringArray(String[] array, String removedString) {
        if(!isInStringArray(array, removedString)) return array; //remove unneeded
        String[] newArray = new String[array.length-1];
        int num = 0;
        for (String old : before) {
            if(old.equals(removedString)) continue;
            newArray[num] = old;
            num++;
        }
        return newArray;
    }

}
