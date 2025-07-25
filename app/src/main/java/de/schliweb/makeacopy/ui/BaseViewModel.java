package de.schliweb.makeacopy.ui;

import android.net.Uri;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * Base ViewModel class that provides common functionality for all ViewModels in the app.
 * This consolidates duplicate code from CameraViewModel and CropViewModel.
 */
public abstract class BaseViewModel extends ViewModel {

    private final MutableLiveData<String> mText;
    private final MutableLiveData<Uri> mImageUri;

    /**
     * Constructor for BaseViewModel
     *
     * @param defaultText The default text to display
     */
    protected BaseViewModel(String defaultText) {
        mText = new MutableLiveData<>();
        mText.setValue(defaultText);

        mImageUri = new MutableLiveData<>();
    }

    /**
     * Gets the text to display
     *
     * @return LiveData containing the text
     */
    public LiveData<String> getText() {
        return mText;
    }

    /**
     * Sets the text to display
     *
     * @param text The text to display
     */
    public void setText(String text) {
        mText.setValue(text);
    }

    /**
     * Gets the URI of the image
     *
     * @return LiveData containing the image URI
     */
    public LiveData<Uri> getImageUri() {
        return mImageUri;
    }

    /**
     * Sets the URI of the image
     *
     * @param uri The URI of the image
     */
    public void setImageUri(Uri uri) {
        mImageUri.setValue(uri);
    }
}