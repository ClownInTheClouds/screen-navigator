package dev.sorokin.screennavigator.pattern;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

/**
 * MVVM: то, что раньше называлось {@code ViewModel} — переехало сюда без изменений по сути.
 */
public abstract class ViewModel {

    protected final PropertyChangeSupport listeners = new PropertyChangeSupport(this);

    protected void notifyListeners(String propertyName, Object oldValue, Object newValue) {
        listeners.firePropertyChange(propertyName, oldValue, newValue);
    }

    public void addListener(PropertyChangeListener listener) {
        listeners.addPropertyChangeListener(listener);
    }

    public void addListener(String propertyName, PropertyChangeListener listener) {
        listeners.addPropertyChangeListener(propertyName, listener);
    }

    public void removeListener(PropertyChangeListener listener) {
        listeners.removePropertyChangeListener(listener);
    }

    public void removeListener(String propertyName, PropertyChangeListener listener) {
        listeners.removePropertyChangeListener(propertyName, listener);
    }
}