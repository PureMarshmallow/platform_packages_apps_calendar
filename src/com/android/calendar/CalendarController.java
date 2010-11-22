/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar;

import static android.provider.Calendar.EVENT_BEGIN_TIME;
import static android.provider.Calendar.EVENT_END_TIME;

import com.android.calendar.event.EditEventActivity;

import android.accounts.Account;
import android.app.Activity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Calendar.Calendars;
import android.provider.Calendar.Events;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.WeakHashMap;

public class CalendarController {
    private static final boolean DEBUG = true;
    private static final String TAG = "CalendarController";
    private static final String REFRESH_SELECTION = Calendars.SYNC_EVENTS + "=?";
    private static final String[] REFRESH_ARGS = new String[] { "1" };
    private static final String REFRESH_ORDER = Calendars._SYNC_ACCOUNT + ","
            + Calendars._SYNC_ACCOUNT_TYPE;

    public static final int MIN_CALENDAR_YEAR = 1970;
    public static final int MAX_CALENDAR_YEAR = 2036;
    public static final int MIN_CALENDAR_WEEK = 0;
    public static final int MAX_CALENDAR_WEEK = 3497; // weeks between 1/1/1970 and 1/1/2037

    private Context mContext;

    // This uses a LinkedHashMap so that we can replace fragments based on the
    // view id they are being expanded into since we can't guarantee a reference
    // to the handler will be findable
    private LinkedHashMap<Integer,EventHandler> eventHandlers =
            new LinkedHashMap<Integer,EventHandler>(5);
    private LinkedList<Integer> mToBeRemovedEventHandlers = new LinkedList<Integer>();
    private LinkedHashMap<Integer, EventHandler> mToBeAddedEventHandlers = new LinkedHashMap<
            Integer, EventHandler>();
    private boolean mDispatchInProgress;

    private static WeakHashMap<Context, CalendarController> instances =
        new WeakHashMap<Context, CalendarController>();

    private WeakHashMap<Object, Long> filters = new WeakHashMap<Object, Long>(1);

    private int mViewType = -1;
    private int mDetailViewType = -1;
    private int mPreviousViewType = -1;
    private long mEventId = -1;
    private Time mTime = new Time();

    private AsyncQueryService mService;

    /**
     * One of the event types that are sent to or from the controller
     */
    public interface EventType {
        final long CREATE_EVENT = 1L;
        final long VIEW_EVENT = 1L << 1;
        final long EDIT_EVENT = 1L << 2;
        final long DELETE_EVENT = 1L << 3;

        final long GO_TO = 1L << 4;

        final long LAUNCH_SETTINGS = 1L << 5;

        final long EVENTS_CHANGED = 1L << 6;

        final long SEARCH = 1L << 7;

        // User has pressed the home key
        final long USER_HOME = 1L << 8;
    }

    /**
     * One of the Agenda/Day/Week/Month view types
     */
    public interface ViewType {
        final int DETAIL = -1;
        final int CURRENT = 0;
        final int AGENDA = 1;
        final int DAY = 2;
        final int WEEK = 3;
        final int MONTH = 4;
        final int EDIT = 5;
    }

    public static class EventInfo {
        public long eventType; // one of the EventType
        public int viewType; // one of the ViewType
        public long id; // event id
        public Time selectedTime; // the selected time in focus
        public Time startTime; // start of a range of time.
        public Time endTime; // end of a range of time.
        public int x; // x coordinate in the activity space
        public int y; // y coordinate in the activity space
        public String query; // query for a user search
        public ComponentName componentName;  // used in combination with query
    }

    // FRAG_TODO remove unneeded api's
    public interface EventHandler {
        long getSupportedEventTypes();
        void handleEvent(EventInfo event);

        /**
         * Returns the time in millis of the selected event in this view.
         * @return the selected time in UTC milliseconds.
         */
        long getSelectedTime();

        /**
         * Changes the view to include the given time.
         * @param time the desired time to view.
         * @animate enable animation
         */
        void goTo(Time time, boolean animate);

        /**
         * Changes the view to include today's date.
         */
        void goToToday();

        /**
         * This is called when the user wants to create a new event and returns
         * true if the new event should default to an all-day event.
         * @return true if the new event should be an all-day event.
         */
        boolean getAllDay();

        /**
         * This notifies the handler that the database has changed and it should
         * update its view.
         */
        void eventsChanged();
    }

    /**
     * Creates and/or returns an instance of CalendarController associated with
     * the supplied context. It is best to pass in the current Activity.
     *
     * @param context The activity if at all possible.
     */
    public static CalendarController getInstance(Context context) {
        synchronized (instances) {
            CalendarController controller = instances.get(context);
            if (controller == null) {
                controller = new CalendarController(context);
                instances.put(context, controller);
            }
            return controller;
        }
    }

    /**
     * Removes an instance when it is no longer needed. This should be called in
     * an activity's onDestroy method.
     *
     * @param context The activity used to create the controller
     */
    public static void removeInstance(Context context) {
        instances.remove(context);
    }

    private CalendarController(Context context) {
        mContext = context;
        mTime.setToNow();
        mDetailViewType = Utils.getSharedPreference(mContext,
                GeneralPreferences.KEY_DETAILED_VIEW,
                GeneralPreferences.DEFAULT_DETAILED_VIEW);
        mService = new AsyncQueryService(context) {
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                new RefreshInBackground().execute(cursor);
            }
        };
    }

    /**
     * Helper for sending New/View/Edit/Delete events
     *
     * @param sender object of the caller
     * @param eventType one of {@link EventType}
     * @param eventId event id
     * @param startMillis start time
     * @param endMillis end time
     * @param x x coordinate in the activity space
     * @param y y coordinate in the activity space
     */
    public void sendEventRelatedEvent(Object sender, long eventType, long eventId, long startMillis,
            long endMillis, int x, int y) {
        EventInfo info = new EventInfo();
        info.eventType = eventType;
        if (eventType == EventType.EDIT_EVENT) {
            info.viewType = ViewType.CURRENT;
        }
        info.id = eventId;
        info.startTime = new Time();
        info.startTime.set(startMillis);
        info.selectedTime = info.startTime;
        info.endTime = new Time();
        info.endTime.set(endMillis);
        info.x = x;
        info.y = y;
        this.sendEvent(sender, info);
    }

    /**
     * Helper for sending non-calendar-event events
     *
     * @param sender object of the caller
     * @param eventType one of {@link EventType}
     * @param start start time
     * @param end end time
     * @param eventId event id
     * @param viewType {@link ViewType}
     */
    public void sendEvent(Object sender, long eventType, Time start, Time end, long eventId,
            int viewType) {
        sendEvent(sender, eventType, start, end, eventId, viewType, null, null);
    }

    /**
     * sendEvent() variant mainly used for search.
     */
    public void sendEvent(Object sender, long eventType, Time start, Time end, long eventId,
            int viewType, String query, ComponentName componentName) {
        EventInfo info = new EventInfo();
        info.eventType = eventType;
        info.startTime = start;
        info.selectedTime = start;
        info.endTime = end;
        info.id = eventId;
        info.viewType = viewType;
        info.query = query;
        info.componentName = componentName;
        this.sendEvent(sender, info);
    }

    public void sendEvent(Object sender, final EventInfo event) {
        // TODO Throw exception on invalid events

        if (DEBUG) {
            Log.d(TAG, eventInfoToString(event));
        }

        Long filteredTypes = filters.get(sender);
        if (filteredTypes != null && (filteredTypes.longValue() & event.eventType) != 0) {
            // Suppress event per filter
            if (DEBUG) {
                Log.d(TAG, "Event suppressed");
            }
            return;
        }

        mPreviousViewType = mViewType;

        // Fix up view if not specified
        if (event.viewType == ViewType.DETAIL) {
            event.viewType = mDetailViewType;
            mViewType = mDetailViewType;
        } else if (event.viewType == ViewType.CURRENT) {
            event.viewType = mViewType;
        } else if (event.viewType != ViewType.EDIT){
            mViewType = event.viewType;

            if (event.viewType == ViewType.AGENDA || event.viewType == ViewType.DAY) {
                mDetailViewType = mViewType;
            }
        }

        // Fix up start time if not specified
        if (event.startTime != null && event.startTime.toMillis(false) != 0) {
            mTime.set(event.startTime);
        }
        event.startTime = mTime;

        // Store the eventId if we're entering edit event
        if ((event.eventType & (EventType.CREATE_EVENT | EventType.EDIT_EVENT)) != 0) {
            if (event.id > 0) {
                mEventId = event.id;
            } else {
                mEventId = -1;
            }
        }

        boolean handled = false;
        synchronized (this) {
            mDispatchInProgress = true;

            if (DEBUG) {
                Log.d(TAG, "sendEvent: Dispatching to " + eventHandlers.size() + " handlers");
            }
            // Dispatch to event handler(s)
            for (Iterator<Entry<Integer, EventHandler>> handlers =
                    eventHandlers.entrySet().iterator(); handlers.hasNext();) {
                Entry<Integer, EventHandler> entry = handlers.next();
                int key = entry.getKey();
                EventHandler eventHandler = entry.getValue();
                if (eventHandler != null
                        && (eventHandler.getSupportedEventTypes() & event.eventType) != 0) {
                    if (mToBeRemovedEventHandlers.contains(key)) {
                        continue;
                    }
                    eventHandler.handleEvent(event);
                    handled = true;
                }
            }

            mDispatchInProgress = false;

            // Deregister removed handlers
            if (mToBeRemovedEventHandlers.size() > 0) {
                for (Integer zombie : mToBeRemovedEventHandlers) {
                    eventHandlers.remove(zombie);
                }
                mToBeRemovedEventHandlers.clear();
            }
            // Add new handlers
            if (mToBeAddedEventHandlers.size() > 0) {
                for (Entry<Integer, EventHandler> food : mToBeAddedEventHandlers.entrySet()) {
                    eventHandlers.put(food.getKey(), food.getValue());
                }
            }
        }

        if (!handled) {
            // Launch Settings
            if (event.eventType == EventType.LAUNCH_SETTINGS) {
                launchSettings();
                return;
            }

            // Create/View/Edit/Delete Event
            long endTime = (event.endTime == null) ? -1 : event.endTime.toMillis(false);
            if (event.eventType == EventType.CREATE_EVENT) {
                launchCreateEvent(event.startTime.toMillis(false), endTime);
                return;
            } else if (event.eventType == EventType.VIEW_EVENT) {
                launchViewEvent(event.id, event.startTime.toMillis(false), endTime);
                return;
            } else if (event.eventType == EventType.EDIT_EVENT) {
                launchEditEvent(event.id, event.startTime.toMillis(false), endTime);
                return;
            } else if (event.eventType == EventType.DELETE_EVENT) {
                launchDeleteEvent(event.id, event.startTime.toMillis(false), endTime);
                return;
            } else if (event.eventType == EventType.SEARCH) {
                launchSearch(event.id, event.query, event.componentName);
                return;
            }
        }
    }

    /**
     * Adds or updates an event handler. This uses a LinkedHashMap so that we can
     * replace fragments based on the view id they are being expanded into.
     *
     * @param key The view id or placeholder for this handler
     * @param eventHandler Typically a fragment or activity in the calendar app
     */
    public void registerEventHandler(int key, EventHandler eventHandler) {
        synchronized (this) {
            if (mDispatchInProgress) {
                mToBeAddedEventHandlers.put(key, eventHandler);
            } else {
                eventHandlers.put(key, eventHandler);
            }
        }
    }

    public void deregisterEventHandler(Integer key) {
        synchronized (this) {
            if (mDispatchInProgress) {
                // To avoid ConcurrencyException, stash away the event handler for now.
                mToBeRemovedEventHandlers.add(key);
            } else {
                eventHandlers.remove(key);
            }
        }
    }

    // FRAG_TODO doesn't work yet
    public void filterBroadcasts(Object sender, long eventTypes) {
        filters.put(sender, eventTypes);
    }

    /**
     * @return the time that this controller is currently pointed at
     */
    public long getTime() {
        return mTime.toMillis(false);
    }

    /**
     * Set the time this controller is currently pointed at
     *
     * @param millisTime Time since epoch in millis
     */
    public void setTime(long millisTime) {
        mTime.set(millisTime);
    }

    /**
     * @return the last event ID the edit view was launched with
     */
    public long getEventId() {
        return mEventId;
    }

    public int getViewType() {
        return mViewType;
    }

    public int getPreviousViewType() {
        return mPreviousViewType;
    }

    private void launchSettings() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(mContext, CalendarSettingsActivity.class.getName());
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mContext.startActivity(intent);
    }

    private void launchCreateEvent(long startMillis, long endMillis) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(mContext, EditEventActivity.class.getName());
        intent.putExtra(EVENT_BEGIN_TIME, startMillis);
        intent.putExtra(EVENT_END_TIME, endMillis);
        mEventId = -1;
        mContext.startActivity(intent);
    }

    private void launchViewEvent(long eventId, long startMillis, long endMillis) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri eventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId);
        intent.setData(eventUri);
        intent.setClassName(mContext, EventInfoActivity.class.getName());
        intent.putExtra(EVENT_BEGIN_TIME, startMillis);
        intent.putExtra(EVENT_END_TIME, endMillis);
        mContext.startActivity(intent);
    }

    private void launchEditEvent(long eventId, long startMillis, long endMillis) {
        Uri uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId);
        Intent intent = new Intent(Intent.ACTION_EDIT, uri);
        intent.putExtra(EVENT_BEGIN_TIME, startMillis);
        intent.putExtra(EVENT_END_TIME, endMillis);
        intent.setClass(mContext, EditEventActivity.class);
        mEventId = eventId;
        mContext.startActivity(intent);
    }

    private void launchDeleteEvent(long eventId, long startMillis, long endMillis) {
        launchDeleteEventAndFinish(null, eventId, startMillis, endMillis, -1);
    }

    private void launchDeleteEventAndFinish(Activity parentActivity, long eventId, long startMillis,
            long endMillis, int deleteWhich) {
        DeleteEventHelper deleteEventHelper = new DeleteEventHelper(mContext, parentActivity,
                parentActivity != null /* exit when done */);
        deleteEventHelper.delete(startMillis, endMillis, eventId, deleteWhich);
    }

    private void launchSearch(long eventId, String query, ComponentName componentName) {
        final SearchManager searchManager =
                (SearchManager)mContext.getSystemService(Context.SEARCH_SERVICE);
        final SearchableInfo searchableInfo = searchManager.getSearchableInfo(componentName);
        final Intent intent = new Intent(Intent.ACTION_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        intent.setComponent(searchableInfo.getSearchActivity());
        mContext.startActivity(intent);
    }

    public void refreshCalendars() {
        Log.d(TAG, "RefreshCalendars starting");
        // get the account, url, and current sync state
        mService.startQuery(mService.getNextToken(), null, Calendars.CONTENT_URI,
                new String[] {Calendars._ID, // 0
                        Calendars._SYNC_ACCOUNT, // 1
                        Calendars._SYNC_ACCOUNT_TYPE, // 2
                        },
                REFRESH_SELECTION, REFRESH_ARGS, REFRESH_ORDER);
    }

    // Forces the viewType. Should only be used for initialization.
    public void setViewType(int viewType) {
        mViewType = viewType;
    }

    // Sets the eventId. Should only be used for initialization.
    public void setEventId(long eventId) {
        mEventId = eventId;
    }

    private class RefreshInBackground extends AsyncTask<Cursor, Integer, Integer> {
        /* (non-Javadoc)
         * @see android.os.AsyncTask#doInBackground(Params[])
         */
        @Override
        protected Integer doInBackground(Cursor... params) {
            if (params.length != 1) {
                return null;
            }
            Cursor cursor = params[0];
            if (cursor == null) {
                return null;
            }

            String previousAccount = null;
            String previousType = null;
            Log.d(TAG, "Refreshing " + cursor.getCount() + " calendars");
            try {
                while (cursor.moveToNext()) {
                    Account account = null;
                    String accountName = cursor.getString(1);
                    String accountType = cursor.getString(2);
                    // Only need to schedule one sync per account and they're
                    // ordered by account,type
                    if (TextUtils.equals(accountName, previousAccount) &&
                            TextUtils.equals(accountType, previousType)) {
                        continue;
                    }
                    previousAccount = accountName;
                    previousType = accountType;
                    account = new Account(accountName, accountType);
                    scheduleSync(account, false /* two-way sync */, null);
                }
            } finally {
                cursor.close();
            }
            return null;
        }

        /**
         * Schedule a calendar sync for the account.
         * @param account the account for which to schedule a sync
         * @param uploadChangesOnly if set, specify that the sync should only send
         *   up local changes.  This is typically used for a local sync, a user override of
         *   too many deletions, or a sync after a calendar is unselected.
         * @param url the url feed for the calendar to sync (may be null, in which case a poll of
         *   all feeds is done.)
         */
        void scheduleSync(Account account, boolean uploadChangesOnly, String url) {
            Bundle extras = new Bundle();
            if (uploadChangesOnly) {
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, uploadChangesOnly);
            }
            if (url != null) {
                extras.putString("feed", url);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            }
            ContentResolver.requestSync(account, Calendars.CONTENT_URI.getAuthority(), extras);
        }
    }

    private String eventInfoToString(EventInfo eventInfo) {
        String tmp = "Unknown";

        StringBuilder builder = new StringBuilder();
        if ((eventInfo.eventType & EventType.GO_TO) != 0) {
            tmp = "Go to time/event";
        } else if ((eventInfo.eventType & EventType.CREATE_EVENT) != 0) {
            tmp = "New event";
        } else if ((eventInfo.eventType & EventType.VIEW_EVENT) != 0) {
            tmp = "View event";
        } else if ((eventInfo.eventType & EventType.EDIT_EVENT) != 0) {
            tmp = "Edit event";
        } else if ((eventInfo.eventType & EventType.DELETE_EVENT) != 0) {
            tmp = "Delete event";
        } else if ((eventInfo.eventType & EventType.LAUNCH_SETTINGS) != 0) {
            tmp = "Launch settings";
        } else if ((eventInfo.eventType & EventType.EVENTS_CHANGED) != 0) {
            tmp = "Refresh events";
        } else if ((eventInfo.eventType & EventType.SEARCH) != 0) {
            tmp = "Search";
        }
        builder.append(tmp);
        builder.append(": id=");
        builder.append(eventInfo.id);
        builder.append(", selected=");
        builder.append(eventInfo.selectedTime);
        builder.append(", start=");
        builder.append(eventInfo.startTime);
        builder.append(", end=");
        builder.append(eventInfo.endTime);
        builder.append(", viewType=");
        builder.append(eventInfo.viewType);
        builder.append(", x=");
        builder.append(eventInfo.x);
        builder.append(", y=");
        builder.append(eventInfo.y);
        return builder.toString();
    }
}
