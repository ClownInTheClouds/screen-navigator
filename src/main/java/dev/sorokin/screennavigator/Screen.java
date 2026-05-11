package dev.sorokin.screennavigator;

import javax.swing.*;

public abstract class Screen<V extends JComponent, VM extends ViewModel, SD> {

    protected final V view;
    protected final VM viewModel;
    protected SD sceneData;

    public Screen(V view, VM viewModel) {
        this.view = view;
        this.viewModel = viewModel;
    }

    public Screen(V view, VM viewModel, SD sceneData) {
        this.view = view;
        this.viewModel = viewModel;
        this.sceneData = sceneData;
    }

    public V getView() {
        return view;
    }

    public VM getViewModel() {
        return viewModel;
    }

    public SD getSceneData() {
        return sceneData;
    }

    public void sceneData(SD sceneData) {
        this.sceneData = sceneData;
    }

    public void onShow() {

    }

    public void onHide() {

    }
}
