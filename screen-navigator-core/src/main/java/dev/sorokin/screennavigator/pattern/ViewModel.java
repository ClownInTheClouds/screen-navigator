package dev.sorokin.screennavigator.pattern;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class ViewModel {

    @FunctionalInterface
    public interface ChangeListener {
        void onChanged(String propertyName, Object oldValue, Object newValue);
    }

    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<ChangeListener>> propertyListeners = new ConcurrentHashMap<>();

    protected void notifyListeners(String propertyName, Object oldValue, Object newValue) {
        for (var listener : listeners) {
            listener.onChanged(propertyName, oldValue, newValue);
        }
        var specific = propertyListeners.get(propertyName);
        if (specific != null) {
            for (var listener : specific) {
                listener.onChanged(propertyName, oldValue, newValue);
            }
        }
    }

    public void addListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void addListener(String propertyName, ChangeListener listener) {
        propertyListeners.computeIfAbsent(propertyName, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void removeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public void removeListener(String propertyName, ChangeListener listener) {
        var specific = propertyListeners.get(propertyName);
        if (specific != null) {
            specific.remove(listener);
        }
    }
}
