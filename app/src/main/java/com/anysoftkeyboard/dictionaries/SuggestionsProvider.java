package com.anysoftkeyboard.dictionaries;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.anysoftkeyboard.base.dictionaries.Dictionary;
import com.anysoftkeyboard.base.dictionaries.EditableDictionary;
import com.anysoftkeyboard.base.dictionaries.KeyCodesProvider;
import com.anysoftkeyboard.base.dictionaries.WordComposer;
import com.anysoftkeyboard.dictionaries.content.ContactsDictionary;
import com.anysoftkeyboard.dictionaries.sqlite.AbbreviationsDictionary;
import com.anysoftkeyboard.dictionaries.sqlite.AutoDictionary;
import com.anysoftkeyboard.nextword.NextWordSuggestions;
import com.anysoftkeyboard.nextword.Utils;
import com.anysoftkeyboard.utils.Logger;
import com.menny.android.anysoftkeyboard.AnyApplication;
import com.menny.android.anysoftkeyboard.BuildConfig;
import com.menny.android.anysoftkeyboard.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class SuggestionsProvider {

    private static final String TAG = "SuggestionsProvider";

    private static final EditableDictionary NullDictionary = new EditableDictionary("NULL") {
        @Override
        public boolean addWord(String word, int frequency) {
            return false;
        }

        @Override
        public void deleteWord(String word) {
        }

        @Override
        public void getWords(KeyCodesProvider composer, WordCallback callback) {
        }

        @Override
        public boolean isValidWord(CharSequence word) {
            return false;
        }

        @Override
        protected void closeAllResources() {
        }

        @Override
        protected void loadAllResources() {
        }
    };

    private static final NextWordSuggestions NULL_NEXT_WORD_SUGGESTIONS = new NextWordSuggestions() {
        @Override
        @NonNull
        public Iterable<String> getNextWords(@NonNull CharSequence currentWord, int maxResults, int minWordUsage) {
            return Collections.emptyList();
        }

        @Override
        public void notifyNextTypedWord(@NonNull CharSequence currentWord) {}

        @Override
        public void resetSentence() {}
    };

    @NonNull
    private final Context mContext;
    @NonNull
    private final List<String> mInitialSuggestionsList = new ArrayList<>();
    private final String mQuickFixesPrefId;
    private final String mContactsDictionaryPrefId;
    private final boolean mContactsDictionaryEnabledDefaultValue;
    @NonNull
    private final List<Dictionary> mMainDictionary = new ArrayList<>();
    @NonNull
    private final List<EditableDictionary> mUserDictionary = new ArrayList<>();
    @NonNull
    private final List<NextWordSuggestions> mUserNextWordDictionary = new ArrayList<>();
    private int mMinWordUsage;
    private boolean mQuickFixesEnabled;
    @NonNull
    private final List<AutoText> mQuickFixesAutoText = new ArrayList<>();
    @Utils.NextWordsSuggestionType
    private String mNextWordSuggestionType = Utils.NEXT_WORD_SUGGESTION_WORDS;
    private int mMaxNextWordSuggestionsCount;
    @NonNull
    private EditableDictionary mAutoDictionary = NullDictionary;
    private boolean mContactsDictionaryEnabled;
    @NonNull
    private Dictionary mContactsDictionary = NullDictionary;

    private boolean mIncognitoMode;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            final Resources resources = mContext.getResources();

            mQuickFixesEnabled = sharedPreferences.getBoolean(mQuickFixesPrefId, true);
            mContactsDictionaryEnabled = sharedPreferences.getBoolean(mContactsDictionaryPrefId, mContactsDictionaryEnabledDefaultValue);
            if (!mContactsDictionaryEnabled) {
                mContactsDictionary.close();
                mContactsDictionary = NullDictionary;
            }

            mMinWordUsage = Utils.getNextWordSuggestionMinUsageFromPrefs(resources, sharedPreferences);
            mNextWordSuggestionType = Utils.getNextWordSuggestionTypeFromPrefs(resources, sharedPreferences);
            mMaxNextWordSuggestionsCount = Utils.getNextWordSuggestionCountFromPrefs(resources, sharedPreferences);
        }
    };

    @NonNull
    private NextWordSuggestions mContactsNextWordDictionary = NULL_NEXT_WORD_SUGGESTIONS;
    private final DictionaryASyncLoader.Listener mContactsDictionaryListener = new DictionaryASyncLoader.Listener() {
        @Override
        public void onDictionaryLoadingDone(Dictionary dictionary) {
        }

        @Override
        public void onDictionaryLoadingFailed(Dictionary dictionary, Exception exception) {
            if (dictionary == mContactsDictionary) {
                mContactsDictionary = NullDictionary;
                mContactsNextWordDictionary = NULL_NEXT_WORD_SUGGESTIONS;
            }
        }
    };
    @NonNull
    private final List<Dictionary> mAbbreviationDictionary = new ArrayList<>();

    public SuggestionsProvider(@NonNull Context context) {
        mContext = context.getApplicationContext();
        final Resources resources = context.getResources();
        mQuickFixesPrefId = resources.getString(R.string.settings_key_quick_fix);
        mContactsDictionaryPrefId = resources.getString(R.string.settings_key_use_contacts_dictionary);
        mContactsDictionaryEnabledDefaultValue = resources.getBoolean(R.bool.settings_default_contacts_dictionary);

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mPrefsChangeListener.onSharedPreferenceChanged(sharedPreferences, null);

        AnyApplication.getConfig().addChangedListener(mPrefsChangeListener);
    }

    private static void allDictionariesClose(List<? extends Dictionary> dictionaries) {
        for (Dictionary dictionary : dictionaries) {
            dictionary.close();
        }

        dictionaries.clear();
    }

    private static boolean allDictionariesIsValid(List<? extends Dictionary> dictionaries, CharSequence word) {
        for (Dictionary dictionary : dictionaries) {
            if (dictionary.isValidWord(word)) return true;
        }

        return false;
    }

    private static void allDictionariesGetWords(List<? extends Dictionary> dictionaries, WordComposer wordComposer, Dictionary.WordCallback wordCallback) {
        for (Dictionary dictionary : dictionaries) {
            dictionary.getWords(wordComposer, wordCallback);
        }
    }

    public void setupSuggestionsForKeyboard(@NonNull List<DictionaryAddOnAndBuilder> dictionaryBuilders) {
        if (BuildConfig.TESTING_BUILD) {
            Logger.d(TAG, "setupSuggestionsFor %d dictionaries", dictionaryBuilders.size());
            for (DictionaryAddOnAndBuilder dictionaryBuilder : dictionaryBuilders) {
                Logger.d(TAG, " * dictionary %s (%s)", dictionaryBuilder.getId(), dictionaryBuilder.getLanguage());
            }
        }

        close();

        for (DictionaryAddOnAndBuilder dictionaryBuilder : dictionaryBuilders) {
            try {
                final Dictionary dictionary = dictionaryBuilder.createDictionary();
                mMainDictionary.add(dictionary);
                DictionaryASyncLoader.executeLoaderParallel(dictionary);
            } catch (Exception e) {
                Logger.e(TAG, e, "Failed to create dictionary %s", dictionaryBuilder.getId());
                e.printStackTrace();
            }
            final UserDictionary userDictionary = createUserDictionaryForLocale(dictionaryBuilder.getLanguage());
            mUserDictionary.add(userDictionary);
            DictionaryASyncLoader.executeLoaderParallel(userDictionary);
            mUserNextWordDictionary.add(userDictionary.getUserNextWordGetter());

            if (mQuickFixesEnabled) {
                final AutoText autoText = dictionaryBuilder.createAutoText();
                if (autoText != null) {
                    mQuickFixesAutoText.add(autoText);
                }
                final AbbreviationsDictionary abbreviationsDictionary = new AbbreviationsDictionary(mContext, dictionaryBuilder.getLanguage());
                mAbbreviationDictionary.add(abbreviationsDictionary);
                DictionaryASyncLoader.executeLoaderParallel(abbreviationsDictionary);
            }

            mInitialSuggestionsList.addAll(dictionaryBuilder.createInitialSuggestions());

            if (AnyApplication.getConfig().getAutoDictionaryInsertionThreshold() > 0 && mAutoDictionary == NullDictionary) {
                //only one auto-dictionary. There is no way to know to which language the typed word belongs.
                mAutoDictionary = new AutoDictionary(mContext, dictionaryBuilder.getLanguage());
                DictionaryASyncLoader.executeLoaderParallel(mAutoDictionary);
            }
        }

        if (mContactsDictionaryEnabled) {
            if (mContactsDictionary == NullDictionary) {
                mContactsDictionary = new ContactsDictionary(mContext);
                mContactsNextWordDictionary = (ContactsDictionary) mContactsDictionary;
                DictionaryASyncLoader.executeLoaderParallel(mContactsDictionaryListener, mContactsDictionary);

            }
        }

    }

    @NonNull
    protected UserDictionary createUserDictionaryForLocale(@NonNull String locale) {
        return new UserDictionary(mContext, locale);
    }

    public void removeWordFromUserDictionary(String word) {
        for (EditableDictionary dictionary : mUserDictionary) {
            dictionary.deleteWord(word);
        }
    }

    public boolean addWordToUserDictionary(String word) {
        if (mIncognitoMode) return false;

        if (mUserDictionary.size() > 0)
            return mUserDictionary.get(0).addWord(word, 128);
        else
            return false;
    }

    public boolean isValidWord(CharSequence word) {
        if (TextUtils.isEmpty(word)) {
            return false;
        }

        return allDictionariesIsValid(mMainDictionary, word) || allDictionariesIsValid(mUserDictionary, word) || mContactsDictionary.isValidWord(word);
    }

    public void setIncognitoMode(boolean incognitoMode) {
        mIncognitoMode = incognitoMode;
    }

    public boolean isIncognitoMode() {
        return mIncognitoMode;
    }

    public void close() {
        Logger.d(TAG, "closeDictionaries");
        allDictionariesClose(mMainDictionary);
        allDictionariesClose(mAbbreviationDictionary);
        mAutoDictionary.close();
        mAutoDictionary = NullDictionary;
        mContactsDictionary.close();
        mContactsDictionary = NullDictionary;
        allDictionariesClose(mUserDictionary);
        mQuickFixesAutoText.clear();
        resetNextWordSentence();
        mContactsNextWordDictionary = NULL_NEXT_WORD_SUGGESTIONS;
        mUserNextWordDictionary.clear();
        mInitialSuggestionsList.clear();
        System.gc();
    }

    public void resetNextWordSentence() {
        for (NextWordSuggestions nextWordSuggestions : mUserNextWordDictionary) {
            nextWordSuggestions.resetSentence();
        }
        mContactsNextWordDictionary.resetSentence();
    }

    public void getSuggestions(WordComposer wordComposer, Dictionary.WordCallback wordCallback) {
        mContactsDictionary.getWords(wordComposer, wordCallback);
        allDictionariesGetWords(mUserDictionary, wordComposer, wordCallback);
        allDictionariesGetWords(mMainDictionary, wordComposer, wordCallback);
    }

    public void getAbbreviations(WordComposer wordComposer, Dictionary.WordCallback wordCallback) {
        allDictionariesGetWords(mAbbreviationDictionary, wordComposer, wordCallback);
    }

    public CharSequence lookupQuickFix(String word) {
        for (AutoText autoText : mQuickFixesAutoText) {
            final String fix = autoText.lookup(word);
            if (fix != null) return fix;
        }

        return null;
    }

    public void getNextWords(String currentWord, Collection<CharSequence> suggestionsHolder, int maxSuggestions) {
        allDictionariesGetNextWord(mUserNextWordDictionary, currentWord, suggestionsHolder, maxSuggestions);
        maxSuggestions = maxSuggestions - suggestionsHolder.size();
        if (maxSuggestions == 0) return;

        for (String nextWordSuggestion : mContactsNextWordDictionary.getNextWords(currentWord, mMaxNextWordSuggestionsCount, mMinWordUsage)) {
            suggestionsHolder.add(nextWordSuggestion);
            maxSuggestions--;
            if (maxSuggestions == 0) return;
        }

        if (Utils.NEXT_WORD_SUGGESTION_WORDS_AND_PUNCTUATIONS.equals(mNextWordSuggestionType)) {
            for (String evenMoreSuggestions : mInitialSuggestionsList) {
                suggestionsHolder.add(evenMoreSuggestions);
                maxSuggestions--;
                if (maxSuggestions == 0) return;
            }
        }
    }

    private void allDictionariesGetNextWord(List<NextWordSuggestions> nextWordDictionaries, String currentWord, Collection<CharSequence> suggestionsHolder, int maxSuggestions) {
        for (NextWordSuggestions nextWordDictionary : nextWordDictionaries) {

            if (!mIncognitoMode) nextWordDictionary.notifyNextTypedWord(currentWord);

            for (String nextWordSuggestion : nextWordDictionary.getNextWords(currentWord, mMaxNextWordSuggestionsCount, mMinWordUsage)) {
                suggestionsHolder.add(nextWordSuggestion);
                maxSuggestions--;
                if (maxSuggestions == 0) return;
            }
        }
    }

    public boolean tryToLearnNewWord(String newWord, int frequencyDelta) {
        if (mIncognitoMode) return false;

        if (!isValidWord(newWord)) {
            return mAutoDictionary.addWord(newWord, frequencyDelta);
        }

        return false;
    }
}
