package fr.gaulupeau.apps.Poche.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import com.mikepenz.aboutlibraries.Libs;
import com.mikepenz.aboutlibraries.LibsBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Map;
import java.util.WeakHashMap;

import fr.gaulupeau.apps.InThePoche.R;
import fr.gaulupeau.apps.Poche.App;
import fr.gaulupeau.apps.Poche.data.DbConnection;
import fr.gaulupeau.apps.Poche.data.Settings;
import fr.gaulupeau.apps.Poche.events.FeedsChangedEvent;
import fr.gaulupeau.apps.Poche.events.UpdateFeedsStartedEvent;
import fr.gaulupeau.apps.Poche.events.UpdateFeedsFinishedEvent;
import fr.gaulupeau.apps.Poche.network.FeedUpdater;
import fr.gaulupeau.apps.Poche.network.WallabagConnection;
import fr.gaulupeau.apps.Poche.service.ServiceHelper;

import static fr.gaulupeau.apps.Poche.data.ListTypes.*;

public class ArticlesListActivity extends AppCompatActivity
        implements ArticlesListFragment.OnFragmentInteractionListener {

    private static final String TAG = ArticlesListActivity.class.getSimpleName();

    private Settings settings;

    private ArticlesListPagerAdapter adapter;
    private ViewPager viewPager;

    private boolean firstTimeShown = true;
    private boolean showEmptyDbDialogOnResume;
    private boolean dbIsEmpty;

    private boolean fullUpdateRunning;
    private int refreshingFragment = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Themes.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_articles_list);

        settings = new Settings(this);

        adapter = new ArticlesListPagerAdapter(getSupportFragmentManager());

        viewPager = (ViewPager) findViewById(R.id.articles_list_pager);
        viewPager.setAdapter(adapter);

        TabLayout tabLayout = (TabLayout) findViewById(R.id.articles_list_tab_layout);

        tabLayout.setTabsFromPagerAdapter(adapter);
        tabLayout.setOnTabSelectedListener(new TabLayout.ViewPagerOnTabSelectedListener(viewPager));
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout) {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);

                fragmentOnShow();
            }
        });

        viewPager.setCurrentItem(1);

        dbIsEmpty = DbConnection.getSession().getArticleDao().queryBuilder().limit(1).count() == 0;
    }

    @Override
    protected void onStart() {
        super.onStart();

        if(dbIsEmpty) {
            showEmptyDbDialogOnResume = true;
        }

        EventBus.getDefault().register(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        updateLists(); // TODO: better update logic (do not update if unnecessary)

        if(firstTimeShown) {
            firstTimeShown = false;

            if(!settings.isConfigurationOk()) {
                settings.setBoolean(Settings.CONFIGURE_OPTIONAL_DIALOG_SHOWN, true);

                AlertDialog.Builder messageBox = new AlertDialog.Builder(this);
                messageBox.setTitle(R.string.firstRun_d_welcome);
                messageBox.setMessage(R.string.firstRun_d_configure);
                messageBox.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(ArticlesListActivity.this, SettingsActivity.class));
                    }
                });
                messageBox.setCancelable(false);
                messageBox.create().show();
            } else if(!settings.getBoolean(Settings.CONFIGURE_OPTIONAL_DIALOG_SHOWN, false)) { // TODO: make all credentials required and remove it
                settings.setBoolean(Settings.CONFIGURE_OPTIONAL_DIALOG_SHOWN, true);

                String username = settings.getString(Settings.USERNAME);
                if(username == null || username.isEmpty()) {
                    AlertDialog.Builder messageBox = new AlertDialog.Builder(this);
                    messageBox.setTitle(R.string.firstRun_d_optionalSettings_title);
                    messageBox.setMessage(R.string.firstRun_d_optionalSettings_message);
                    messageBox.setPositiveButton(R.string.go_to_settings, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startActivity(new Intent(ArticlesListActivity.this, SettingsActivity.class));
                        }
                    });
                    messageBox.setNegativeButton(R.string.dismiss, null);
                    messageBox.create().show();
                }
            }
        }

        if(showEmptyDbDialogOnResume) {
            showEmptyDbDialogOnResume = false;

            if(settings.isConfigurationOk()) {
                AlertDialog.Builder messageBox = new AlertDialog.Builder(ArticlesListActivity.this);
                messageBox.setTitle(R.string.d_emptyDB_title);
                messageBox.setMessage(R.string.d_emptyDB_text);
                messageBox.setPositiveButton(R.string.d_emptyDB_answer_updateAll, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        fullUpdate();
                    }
                });
                messageBox.setNegativeButton(R.string.negative_answer, null);
                messageBox.create().show();
            }
        }
    }

    @Override
    protected void onStop() {
        EventBus.getDefault().unregister(this);

        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuFullUpdate:
                fullUpdate();
                return true;
            case R.id.menuSettings:
                startActivity(new Intent(getBaseContext(), SettingsActivity.class));
                return true;
            case R.id.menuBagPage:
                startActivity(new Intent(getBaseContext(), AddActivity.class));
                return true;
            case R.id.menuOpenRandomArticle:
                openRandomArticle();
                return true;
            case R.id.menuSyncQueue:
                syncQueue();
                return true;
            case R.id.menuAbout:
                new LibsBuilder()
                        //provide a style (optional) (LIGHT, DARK, LIGHT_DARK_TOOLBAR)
                        .withActivityStyle(Libs.ActivityStyle.LIGHT_DARK_TOOLBAR)
                        .withAboutIconShown(true)
                        .withAboutVersionShown(true)
                        .withAboutDescription(getResources().getString(R.string.aboutText))
                        .start(this);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedsChangedEvent(FeedsChangedEvent event) {
        Log.d(TAG, "Got FeedsChangedEvent");

        updateListByFeedType(event.getFeedType());
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onUpdateFeedsStartedEvent(UpdateFeedsStartedEvent event) {
        Log.d(TAG, "Got UpdateFeedsStartedEvent");

        notifyListUpdate(event.getFeedType(), true);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onUpdateFeedsStoppedEvent(UpdateFeedsFinishedEvent event) {
        Log.d(TAG, "Got UpdateFeedsFinishedEvent");

        // TODO: better "empty DB" suggestion logic
        dbIsEmpty = false;
        showEmptyDbDialogOnResume = false;

        notifyListUpdate(event.getFeedType(), false);
    }

    @Override
    public boolean isFullUpdateRunning() {
        return fullUpdateRunning;
    }

    @Override
    public boolean isCurrentFeedUpdating() {
        return refreshingFragment != -1 && refreshingFragment == viewPager.getCurrentItem();
    }

    @Override
    public void updateFeed() {
        int position = viewPager.getCurrentItem();
        FeedUpdater.FeedType feedType = ArticlesListPagerAdapter.getFeedType(position);
        FeedUpdater.UpdateType updateType = feedType == FeedUpdater.FeedType.Main
                ? FeedUpdater.UpdateType.Fast : FeedUpdater.UpdateType.Full;
        // TODO: fix
        if(updateFeed(true, feedType, updateType)) {
//            refreshingFragment = position;
//            setRefreshingUI(true);
        } else {
            setRefreshingUI(false);
        }
    }

    private void notifyListUpdate(FeedUpdater.FeedType feedType, boolean started) {
        int position = ArticlesListPagerAdapter.positionByFeedType(feedType);

        if(started) {
            if(position != -1) {
                if(refreshingFragment != -1 && refreshingFragment != position) {
                    setRefreshingUI(false, refreshingFragment); // should not happen
                }

                refreshingFragment = position;
                setRefreshingUI(true, position);
            } else {
                fullUpdateRunning = true;
                setRefreshingUI(true);
            }
        } else {
            if(refreshingFragment != -1) {
                setRefreshingUI(false, refreshingFragment);
                refreshingFragment = -1;
            }
            if(fullUpdateRunning) {
                fullUpdateRunning = false;
                setRefreshingUI(false);
            }
        }
    }

    private void fullUpdate() {
        if(updateFeed(true, null, null)) {
            fullUpdateRunning = true;
            setRefreshingUI(true);
        } else {
            setRefreshingUI(false);
        }
    }

    private boolean updateFeed(boolean showErrors,
                               FeedUpdater.FeedType feedType,
                               FeedUpdater.UpdateType updateType) {
        boolean result = false;

        // TODO: rewrite?
        if(fullUpdateRunning || refreshingFragment != -1) {
            Toast.makeText(this, R.string.updateFeed_previousUpdateNotFinished, Toast.LENGTH_SHORT)
                    .show();
        } else if(!settings.isConfigurationOk()) {
            if(showErrors) {
                Toast.makeText(this, getString(R.string.txtConfigNotSet), Toast.LENGTH_SHORT).show();
            }
        } else if(WallabagConnection.isNetworkOnline()) {
            ServiceHelper.updateFeed(this, feedType, updateType);

            result = true;
        } else {
            if(showErrors) {
                Toast.makeText(this, getString(R.string.txtNetOffline), Toast.LENGTH_SHORT).show();
            }
        }

        return result;
    }

    private void updateLists() {
        if(adapter != null) {
            for(int i = 0; i < ArticlesListPagerAdapter.PAGES.length; i++) {
                ArticlesListFragment f = adapter.getCachedFragment(i);
                if(f != null) {
                    f.updateList();
                }
            }
        }
    }

    private void updateList(int position) {
        ArticlesListFragment f = getFragment(position);
        if(f != null) {
            f.updateList();
        }
    }

    private void updateListByFeedType(FeedUpdater.FeedType feedType) {
        int position = ArticlesListPagerAdapter.positionByFeedType(feedType);

        if(position != -1) {
            ArticlesListFragment f = getFragment(position);
            if(f != null) {
                f.updateList();
            }
        } else {
            updateLists();
        }
    }

    private void setRefreshingUI(boolean refreshing) {
        ArticlesListFragment f = getCurrentFragment();
        if(f != null) {
            f.setRefreshingUI(refreshing);
        }
    }

    private void setRefreshingUI(boolean refreshing, int position) {
        ArticlesListFragment f = getFragment(position);
        if(f != null) {
            f.setRefreshingUI(refreshing);
        }
    }

    private void openRandomArticle() {
        ArticlesListFragment f = getCurrentFragment();
        if(f != null) {
            f.openRandomArticle();
        }
    }

    private void fragmentOnShow() {
        ArticlesListFragment f = getCurrentFragment();
        if(f != null) {
            f.onShow();
        }
    }

    private ArticlesListFragment getCurrentFragment() {
        return adapter == null || viewPager == null ? null
                : adapter.getCachedFragment(viewPager.getCurrentItem());
    }

    private ArticlesListFragment getFragment(int position) {
        return adapter != null ? adapter.getCachedFragment(position) : null;
    }

    private void syncQueue() {
        if(!WallabagConnection.isNetworkOnline()) {
            Toast.makeText(this, getString(R.string.txtNetOffline), Toast.LENGTH_SHORT).show();
            return;
        }

        ServiceHelper.syncQueue(this);
    }

    public static class ArticlesListPagerAdapter extends FragmentPagerAdapter {

        private static final String TAG = "ArtListPagerAdapter";

        private static int[] PAGES = {
                LIST_TYPE_FAVORITES,
                LIST_TYPE_UNREAD,
                LIST_TYPE_ARCHIVED
        };

        private Map<Integer, ArticlesListFragment> fragments = new WeakHashMap<>(3);

        public ArticlesListPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            Log.d(TAG, "getItem " + position);
            return getFragment(position);
        }

        @Override
        public int getCount() {
            return PAGES.length;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch(PAGES[position]) {
                case LIST_TYPE_FAVORITES:
                    return App.getInstance().getString(R.string.feedName_favorites);
                case LIST_TYPE_ARCHIVED:
                    return App.getInstance().getString(R.string.feedName_archived);
                default:
                    return App.getInstance().getString(R.string.feedName_unread);
            }
        }

        public static FeedUpdater.FeedType getFeedType(int position) {
            switch(ArticlesListPagerAdapter.PAGES[position]) {
                case LIST_TYPE_FAVORITES:
                    return FeedUpdater.FeedType.Favorite;
                case LIST_TYPE_ARCHIVED:
                    return FeedUpdater.FeedType.Archive;
                default:
                    return FeedUpdater.FeedType.Main;
            }
        }

        public static int positionByFeedType(FeedUpdater.FeedType feedType) {
            if(feedType == null) return -1;

            int listType;
            switch(feedType) {
                case Favorite:
                    listType = LIST_TYPE_FAVORITES;
                    break;
                case Archive:
                    listType = LIST_TYPE_ARCHIVED;
                    break;
                default:
                    listType = LIST_TYPE_UNREAD;
                    break;
            }

            for(int i = 0; i < PAGES.length; i++) {
                if(listType == PAGES[i]) return i;
            }

            return -1;
        }

        public ArticlesListFragment getCachedFragment(int position) {
            return fragments.get(position);
        }

        private ArticlesListFragment getFragment(int position) {
            Log.d(TAG, "getFragment " + position);
            ArticlesListFragment f = fragments.get(position);
            if(f == null) {
                Log.d(TAG, "creating new instance");
                f = ArticlesListFragment.newInstance(PAGES[position]);
                fragments.put(position, f);
            }

            return f;
        }

    }

}
