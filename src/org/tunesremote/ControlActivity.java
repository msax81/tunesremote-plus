/*
    TunesRemote+ - http://code.google.com/p/tunesremote-plus/

    Copyright (C) 2008 Jeffrey Sharkey, http://jsharkey.org/
    Copyright (C) 2010 TunesRemote+, http://code.google.com/p/tunesremote-plus/

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

    The Initial Developer of the Original Code is Jeffrey Sharkey.
    Portions created by Jeffrey Sharkey are
    Copyright (C) 2008. Jeffrey Sharkey, http://jsharkey.org/
    All Rights Reserved.
 */

package org.tunesremote;

import java.util.ArrayList;
import java.util.List;

import org.tunesremote.daap.Response;
import org.tunesremote.daap.Session;
import org.tunesremote.daap.Speaker;
import org.tunesremote.daap.Status;
import org.tunesremote.util.ThreadExecutor;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.gesture.Gesture;
import android.gesture.GestureLibraries;
import android.gesture.GestureLibrary;
import android.gesture.GestureOverlayView;
import android.gesture.Prediction;
import android.gesture.GestureOverlayView.OnGesturePerformedListener;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LevelListDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RatingBar;
import android.widget.RatingBar.OnRatingBarChangeListener;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Main activity of TunesRemote. This controls the player and drives all the
 * other activities.
 * <p>
 */
public class ControlActivity extends Activity implements OnGesturePerformedListener {

   public final static String TAG = ControlActivity.class.toString();

   public static final int NOTIFICATION_CONTROL = 1;

   /**
    * ID of the speakers dialog
    */
   private static final int DIALOG_SPEAKERS = 1;
   public final static int VIBRATE_LEN = 150;
   // keep volume cache for 10 seconds
   public final static long CACHE_TIME = 10000;
   public final static String EULA = "eula";

   protected static BackendService backend;
   protected static Session session;
   protected static Status status;
   protected String showingAlbumId = null;
   protected RatingBar ratingBar;
   protected RelativeLayout ratingBox;
   protected TextView trackName, trackArtist, trackAlbum, seekPosition, seekRemain;
   protected SeekBar seekBar;
   protected ImageView coverImage;
   protected ImageButton controlPrev, controlPause, controlNext, controlShuffle, controlRepeat;
   protected View volume;
   protected ProgressBar volumeBar;
   protected GestureOverlayView gestures;
   protected Toast volumeToast;
   protected FadeView fadeview;
   protected Toast shuffleToast;
   protected Toast repeatToast;
   protected GestureLibrary gestureLib;
   protected boolean dragging = false, agreed = false, autoPause = false, stayConnected = false, fadeDetails = true,
            fadeUpNew = true, vibrate = true, cropImage = true, fullScreen = true, ignoreNextTick = false, showRatingBox = true,
            showToast = true, invertGestures = false;
   protected Vibrator vibrator;
   protected SharedPreferences prefs;
   protected long cachedTime = -1;
   protected long cachedVolume = -1;
   protected LevelListDrawable shuffle, repeat, play;

   /**
    * List of available speakers
    */
   protected final static List<Speaker> SPEAKERS = new ArrayList<Speaker>();

   /**
    * Instance of the speaker list adapter used in the speakers dialog
    */
   protected SpeakersAdapter speakersAdapter;

   public ServiceConnection connection = new ServiceConnection() {
      public void onServiceConnected(ComponentName className, final IBinder service) {

         ThreadExecutor.runTask(new Runnable() {

            public void run() {
               try {
                  backend = ((BackendService.BackendBinder) service).getService();
                  SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(ControlActivity.this);
                  backend.setPrefs(settings);

                  if (!agreed)
                     return;

                  Log.w(TAG, "onServiceConnected");

                  session = backend.getSession();
                  if (session == null) {
                     // we could not connect with library, so launch picker
                     ControlActivity.this.startActivityForResult(
                              new Intent(ControlActivity.this, LibraryActivity.class), 1);

                  } else {
                     // for some reason we are not correctly disposing of the
                     // session threads we create so we purge any existing ones
                     // before creating a new one
                     status = session.singletonStatus(statusUpdate);
                     status.updateHandler(statusUpdate);

                     // push update through to make sure we get updated
                     statusUpdate.sendEmptyMessage(Status.UPDATE_SPEAKERS);
                     statusUpdate.sendEmptyMessage(Status.UPDATE_TRACK);
                  }
               } catch (Throwable e) {
                  Log.e(TAG, "onServiceConnected:" + e);
               }
            }
         });

      }

      public void onServiceDisconnected(ComponentName className) {
         // make sure we clean up our handler-specific status
         Log.w(TAG, "onServiceDisconnected");
         status.updateHandler(null);
         backend = null;
         status = null;
      }
   };

   protected Handler statusUpdate = new Handler() {

      @Override
      public void handleMessage(Message msg) {
         // update gui based on severity
         switch (msg.what) {
         case Status.UPDATE_TRACK:
            trackName.setText(status.getTrackName());
            trackArtist.setText(status.getTrackArtist());
            trackAlbum.setText(status.getTrackAlbum());

            // Set to invisible until rating is known
            //removed this to prevent rating bar flickering, this way the rating will just appear
            //when it's known -chrisjdowd@gmail.com
            //ratingBar.setVisibility(RatingBar.INVISIBLE);

            // fade new details up if requested
            if (fadeUpNew)
               fadeview.keepAwake();

         case Status.UPDATE_COVER:
            boolean forced = (msg.what == Status.UPDATE_COVER);
            boolean shouldUpdate = (!status.albumId.equals(showingAlbumId)) && !status.coverEmpty;
            if (forced)
               shouldUpdate = true;

            // only update coverart if different than already shown
            Log.d(TAG, String.format("Artwork for albumid=%s, value=%s, what=%d", status.albumId, status.coverCache,
                     msg.what));
            if (shouldUpdate) {
               if (status.coverEmpty) {
                  // fade down if no coverart
                  coverImage.setImageDrawable(new ColorDrawable(Color.BLACK));
               } else if (status.coverCache != null) {
                  // fade over to new coverart
                  Drawable one = coverImage.getDrawable();
                  if (one != null) {
                     TransitionDrawable trans = new TransitionDrawable(new Drawable[] { one,
                              new BitmapDrawable(getResources(), status.coverCache) });
                     coverImage.setImageDrawable(trans);
                     trans.startTransition(1000);
                  } else {
                     coverImage.setImageDrawable(new BitmapDrawable(getResources(), status.coverCache));
                  }
                  one = null;
               }
               showingAlbumId = status.albumId;
            }

         case Status.UPDATE_STATE:
            controlPause.setImageResource((status.getPlayStatus() == Status.STATE_PLAYING) ? R.drawable.btn_pause
                     : R.drawable.btn_play);
            seekBar.setMax(status.getProgressTotal());

         case Status.UPDATE_PROGRESS:
            if (ignoreNextTick) {
               ignoreNextTick = false;
               return;
            }
            seekPosition.setText(Response.convertTime(status.getProgress() * 1000));
            seekRemain.setText("-" + Response.convertTime(status.getRemaining() * 1000));
            if (!dragging) {
               seekBar.setProgress(status.getProgress());
            }
            break;

         // This one is triggered by a thread, so should not be used to update
         // progress, etc...
         case Status.UPDATE_RATING:
            long rating = status.getRating();
            if (rating >= 0) {
               ratingBar.setRating(((float) status.getRating() / 100) * 5);
               ratingBar.setVisibility(RatingBar.VISIBLE);

               // fade new details up if requested
               if (fadeUpNew)
                  fadeview.keepAwake();
            }
            break;
         case Status.UPDATE_SPEAKERS:
            ThreadExecutor.runTask(new Runnable() {
               public void run() {
                  try {
                     if (status == null) {
                        return;
                     }
                     status.getSpeakers(SPEAKERS);
                  } catch (Exception e) {
                     Log.e(TAG, "Speaker Exception:" + e.getMessage());
                  }
               }

            });
            break;
         }

         checkShuffle();
         checkRepeat();
      }
   };

   public android.telephony.PhoneStateListener psListener = new android.telephony.PhoneStateListener() {
      private boolean wasPlaying = false;

      @Override
      public void onCallStateChanged(int state, java.lang.String incomingNumber) {

         if (ControlActivity.this.autoPause) {
            switch (state) {
            case android.telephony.TelephonyManager.CALL_STATE_IDLE:
               if (wasPlaying && session != null && ControlActivity.status.getPlayStatus() == Status.STATE_PAUSED) {
                  session.controlPlay();
                  wasPlaying = false;
               }
               break;
            case android.telephony.TelephonyManager.CALL_STATE_OFFHOOK:
               if (session != null && ControlActivity.status.getPlayStatus() == Status.STATE_PLAYING) {
                  session.controlPause();
                  wasPlaying = true;
               }
               break;
            case android.telephony.TelephonyManager.CALL_STATE_RINGING:
               if (session != null && ControlActivity.status.getPlayStatus() == Status.STATE_PLAYING) {
                  session.controlPause();
                  wasPlaying = true;
               }
               break;
            default:
               break;
            }
         }
      }

   };

   protected void StartNowPlaying() {
      if (status == null)
         return;

      ThreadExecutor.runTask(new Runnable() {

         public void run() {
            try {
               // launch tracks view for current album
               Intent intent = new Intent(ControlActivity.this, NowPlayingActivity.class);
               intent.putExtra(Intent.EXTRA_TITLE, status.getAlbumId());
               ControlActivity.this.startActivity(intent);
            } catch (Exception e) {
               Log.e(TAG, "StartNowPlaying:" + e.getMessage());
            }
         }

      });
   }

   protected Handler doubleTapHandler = new Handler() {

      @Override
      public void handleMessage(Message msg) {
         StartNowPlaying();
      }
   };

   @Override
   public void onStart() {
      super.onStart();

      this.stayConnected = this.prefs.getBoolean(this.getString(R.string.pref_background), this.stayConnected);
      this.fadeDetails = this.prefs.getBoolean(this.getString(R.string.pref_fade), this.fadeDetails);
      this.fadeUpNew = this.prefs.getBoolean(this.getString(R.string.pref_fadeupnew), this.fadeUpNew);
      this.vibrate = this.prefs.getBoolean(this.getString(R.string.pref_vibrate), this.vibrate);
      this.autoPause = this.prefs.getBoolean(this.getString(R.string.pref_autopause), this.autoPause);
      this.showRatingBox = this.prefs.getBoolean(this.getString(R.string.pref_showrating), true);
      this.showToast = this.prefs.getBoolean(this.getString(R.string.pref_showtoast), true);
      this.invertGestures = this.prefs.getBoolean(getResources().getString(R.string.pref_invertgestures), false);
      
      this.fadeview.allowFade = this.fadeDetails;
      this.fadeview.keepAwake();

      Intent service = new Intent(this, BackendService.class);

      if (this.stayConnected) {
         // if were running background service, start now
         this.startService(service);

      } else {
         // otherwise make sure we kill the static background service
         this.stopService(service);

      }

      // regardless of stayConnected, were always going to need a bound backend
      // for this activity
      this.bindService(service, connection, Context.BIND_AUTO_CREATE);

      android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) ControlActivity.this
               .getSystemService(android.content.Context.TELEPHONY_SERVICE);
      tm.listen(psListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE);

   }

   @Override
   public void onStop() {
      super.onStop();
      Log.w(TAG, "Stopping TunesRemote...");
      try {
         if (!this.stayConnected && session != null) {
            session.purgeAllStatus();
         }

         this.unbindService(connection);
      } catch (Exception ex) {
         Log.e(TAG, ex.getMessage(), ex);
      }
   }

   @Override
   protected void onDestroy() {
      super.onDestroy();
      Log.w(TAG, "Destroying TunesRemote...");
      try {
         if (session != null) {
            session.purgeAllStatus();
            session.logout();
            session = null;
         }
         backend = null;
      } catch (Exception ex) {
         Log.e(TAG, ex.getMessage(), ex);
      }
      Log.w(TAG, "Destroyed TunesRemote!");
   }

   @Override
   protected void onActivityResult(int requestCode, int resultCode, Intent data) {

      if (resultCode == Activity.RESULT_OK) {
         // yay they agreed, so store that info
         Editor edit = prefs.edit();
         edit.putBoolean(EULA, true);
         edit.commit();
         this.agreed = true;
      } else {
         // user didnt agree, so close
         this.finish();
      }

   }

   /**
    * OnSeekBarChangeListener that controls the volume for a certain speaker
    * @author Daniel Thommes
    */
   public class VolumeSeekBarListener implements OnSeekBarChangeListener {
      private final Speaker speaker;

      public VolumeSeekBarListener(Speaker speaker) {
         this.speaker = speaker;
      }

      public void onStopTrackingTouch(SeekBar seekBar) {
         final int newVolume = seekBar.getProgress();
         ThreadExecutor.runTask(new Runnable() {
            public void run() {
               try {
                  // Volume of the loudest speaker
                  int maxVolume = 0;
                  // Volume of the second loudest speaker
                  int secondMaxVolume = 0;
                  for (Speaker speaker : SPEAKERS) {
                     if (speaker.getAbsoluteVolume() > maxVolume) {
                        secondMaxVolume = maxVolume;
                        maxVolume = speaker.getAbsoluteVolume();
                     } else if (speaker.getAbsoluteVolume() > secondMaxVolume) {
                        secondMaxVolume = speaker.getAbsoluteVolume();
                     }
                  }
                  // fetch the master volume if necessary
                  checkCachedVolume();
                  int formerVolume = speaker.getAbsoluteVolume();
                  status.setSpeakerVolume(speaker.getId(), newVolume, formerVolume, maxVolume, secondMaxVolume,
                           cachedVolume);
                  speaker.setAbsoluteVolume(newVolume);
               } catch (Exception e) {
                  Log.e(TAG, "Speaker Exception:" + e.getMessage());
               }
            }

         });
      }

      public void onStartTrackingTouch(SeekBar seekBar) {
      }

      public void onProgressChanged(SeekBar seekBar, int newVolume, boolean fromUser) {
      }
   }

   /**
    * List Adapter for displaying the list of available speakers.
    * @author Daniel Thommes
    */
   public class SpeakersAdapter extends BaseAdapter {

      private final LayoutInflater inflater;

      public SpeakersAdapter(Context context) {
         inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
      }

      public int getCount() {
         if (SPEAKERS == null) {
            return 0;
         }
         return SPEAKERS.size();
      }

      public Object getItem(int position) {
         return SPEAKERS.get(position);
      }

      public long getItemId(int position) {
         return position;
      }

      /**
       * Toggles activation of a given speaker and refreshes the view
       * @param active Flag indicating, whether the speaker shall be activated
       * @param speaker the speaker to be activated or deactivated
       */
      public void setSpeakerActive(boolean active, final Speaker speaker) {
         if (speaker == null) {
            return;
         }
         if (status == null) {
            return;
         }
         speaker.setActive(active);

         ThreadExecutor.runTask(new Runnable() {
            public void run() {
               try {
                  status.setSpeakers(SPEAKERS);
               } catch (Exception e) {
                  Log.e(TAG, "Speaker Exception:" + e.getMessage());
               }
            }

         });

         notifyDataSetChanged();
      }

      public View getView(int position, View convertView, ViewGroup parent) {
         try {

            View row;
            if (null == convertView) {
               row = inflater.inflate(R.layout.item_speaker, null);
            } else {
               row = convertView;
            }

            /*************************************************************
             * Find the necessary sub views
             *************************************************************/
            TextView nameTextview = (TextView) row.findViewById(R.id.speakerNameTextView);
            TextView speakerTypeTextView = (TextView) row.findViewById(R.id.speakerTypeTextView);
            final CheckBox activeCheckBox = (CheckBox) row.findViewById(R.id.speakerActiveCheckBox);
            SeekBar volumeBar = (SeekBar) row.findViewById(R.id.speakerVolumeBar);

            /*************************************************************
             * Set view properties
             *************************************************************/
            final Speaker speaker = SPEAKERS.get(position);
            nameTextview.setText(speaker.getName());
            speakerTypeTextView.setText(speaker.isLocalSpeaker() ? R.string.speakers_dialog_computer_speaker
                     : R.string.speakers_dialog_airport_express);
            activeCheckBox.setChecked(speaker.isActive());
            activeCheckBox.setOnClickListener(new OnClickListener() {

               public void onClick(View v) {
                  setSpeakerActive(activeCheckBox.isChecked(), speaker);
               }
            });
            nameTextview.setOnClickListener(new OnClickListener() {

               public void onClick(View v) {
                  activeCheckBox.toggle();
                  setSpeakerActive(activeCheckBox.isChecked(), speaker);
               }
            });
            speakerTypeTextView.setOnClickListener(new OnClickListener() {

               public void onClick(View v) {
                  activeCheckBox.toggle();
                  setSpeakerActive(activeCheckBox.isChecked(), speaker);
               }
            });
            // If the speaker is active, enable the volume bar
            if (speaker.isActive()) {
               volumeBar.setEnabled(true);
               volumeBar.setProgress(speaker.getAbsoluteVolume());
               volumeBar.setOnSeekBarChangeListener(new VolumeSeekBarListener(speaker));
            } else {
               volumeBar.setEnabled(false);
               volumeBar.setProgress(0);
            }
            return row;
         } catch (RuntimeException e) {
            Log.e(TAG, "Error when rendering speaker item: ", e);
            throw e;
         }
      }
   }

   @Override
   protected Dialog onCreateDialog(int id) {
      if (id == DIALOG_SPEAKERS) {
         // Create the speakers dialog (once)
         return new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_lock_silent_mode_off)
                  .setTitle(R.string.control_menu_speakers).setAdapter(speakersAdapter, null)
                  .setPositiveButton("OK", null).create();
      }
      return null;
   }

   @Override
   protected void onResume() {
      this.fullScreen = this.prefs.getBoolean(this.getString(R.string.pref_fullscreen), true);
      this.showRatingBox = this.prefs.getBoolean(this.getString(R.string.pref_showrating), true);
      this.showToast = this.prefs.getBoolean(getResources().getString(R.string.pref_showtoast), true);
      this.invertGestures = this.prefs.getBoolean(getResources().getString(R.string.pref_invertgestures), false);
      if(!showRatingBox){
    	  ratingBox.setVisibility(View.GONE);
      }
      else{
    	  ratingBox.setVisibility(View.VISIBLE);
      }
      if (this.fullScreen) {
         getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
         getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
      } else {
         getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
         getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
      }
      this.cropImage = this.prefs.getBoolean(this.getString(R.string.pref_cropimage), true);
      if (this.cropImage) {
         this.coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
      } else {
         this.coverImage.setScaleType(ImageView.ScaleType.FIT_XY);
      }
      super.onResume();
   }

   @TargetApi(11)
   @SuppressLint("NewApi")
   @Override
   public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);

      // before we go any further, make sure theyve agreed to EULA
      this.prefs = PreferenceManager.getDefaultSharedPreferences(this);
      this.agreed = prefs.getBoolean(EULA, false);
      if (this.prefs.getBoolean(this.getString(R.string.pref_fullscreen), true)
               && Build.VERSION.SDK_INT <= Build.VERSION_CODES.HONEYCOMB) {
         // Can't use this above Honeycomb, hides the ActionBar making
         // control impossible
         this.requestWindowFeature(Window.FEATURE_NO_TITLE);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
         // Instead, make the ActionBar translucent
         this.requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
         getActionBar().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#AA000000")));
      }
      if (!this.agreed) {
         // show eula wizard
         this.startActivityForResult(new Intent(this, WizardActivity.class), 1);
      }

      setContentView(R.layout.act_control);

      this.vibrator = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);

      // prepare volume toast view
      LayoutInflater inflater = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

      this.volume = inflater.inflate(R.layout.toa_volume, null, false);

      this.volumeBar = (ProgressBar) this.volume.findViewById(R.id.volume);

      this.volumeToast = new Toast(this);
      this.volumeToast.setDuration(Toast.LENGTH_SHORT);
      this.volumeToast.setGravity(Gravity.CENTER, 0, 0);
      this.volumeToast.setView(this.volume);

      this.shuffleToast = Toast.makeText(this, R.string.control_menu_shuffle_off, Toast.LENGTH_SHORT);
      this.shuffleToast.setGravity(Gravity.CENTER, 0, 0);

      this.repeatToast = Toast.makeText(this, R.string.control_menu_repeat_none, Toast.LENGTH_SHORT);
      this.repeatToast.setGravity(Gravity.CENTER, 0, 0);

      this.showToast = this.prefs.getBoolean(getResources().getString(R.string.pref_showtoast), true);

      // pull out interesting controls
      this.trackName = (TextView) findViewById(R.id.info_title);
      this.trackArtist = (TextView) findViewById(R.id.info_artist);
      this.trackAlbum = (TextView) findViewById(R.id.info_album);
      this.ratingBar = (RatingBar) findViewById(R.id.rating_bar);
      this.ratingBox = (RelativeLayout) findViewById(R.id.rating_box);
      this.showRatingBox = prefs.getBoolean(this.getString(R.string.pref_showrating), true);
      if(!this.showRatingBox){
    	  ratingBox.setVisibility(View.GONE);
      }
      
      this.coverImage = (ImageView) findViewById(R.id.cover);
      this.cropImage = this.prefs.getBoolean(this.getString(R.string.pref_cropimage), true);
      if (this.cropImage) {
         this.coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
      } else {
         this.coverImage.setScaleType(ImageView.ScaleType.FIT_XY);
      }
      
      //gesture stuff
      this.gestures = (GestureOverlayView) findViewById(R.id.control_gestures);
      gestures.addOnGesturePerformedListener(this);
      gestureLib = GestureLibraries.fromRawResource(this, R.raw.gestures);
      if(!gestureLib.load()){
    	  Toast.makeText(this, "Unable to load gestures", Toast.LENGTH_SHORT).show();
      }

      this.seekBar = (SeekBar) findViewById(R.id.seek);
      this.seekPosition = (TextView) findViewById(R.id.seek_position);
      this.seekRemain = (TextView) findViewById(R.id.seek_remain);

      this.controlPrev = (ImageButton) findViewById(R.id.control_prev);
      this.controlPause = (ImageButton) findViewById(R.id.control_pause);
      this.controlNext = (ImageButton) findViewById(R.id.control_next);

      this.controlShuffle = (ImageButton) findViewById(R.id.control_shuffle);
      this.controlRepeat = (ImageButton) findViewById(R.id.control_repeat);

      shuffle = (LevelListDrawable) controlShuffle.getDrawable();
      repeat = (LevelListDrawable) controlRepeat.getDrawable();

      this.seekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
         public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {
         }

         public void onStartTrackingTouch(SeekBar seekBar) {
            dragging = true;
         }

         public void onStopTrackingTouch(SeekBar seekBar) {
            dragging = false;
            if (session == null || seekBar == null || vibrator == null) {
               return;
            }

            // scan to location in song
            session.controlProgress(seekBar.getProgress());
            ignoreNextTick = true;
            if (vibrate)
               vibrator.vibrate(VIBRATE_LEN);
         }
      });

      this.controlPrev.setOnClickListener(new OnClickListener() {
         public void onClick(View v) {
            if (session == null) {
               return;
            }
            session.controlPrev();
            if (vibrate)
               vibrator.vibrate(VIBRATE_LEN);
         }
      });

      this.controlNext.setOnClickListener(new OnClickListener() {
         public void onClick(View v) {
            if (session == null) {
               return;
            }
            session.controlNext();
            if (vibrate)
               vibrator.vibrate(VIBRATE_LEN);
         }
      });

      this.controlPause.setOnClickListener(new OnClickListener() {
         public void onClick(View v) {
            if (session == null) {
               return;
            }
            if (ControlActivity.status.getPlayStatus() == Status.STATE_PLAYING) {
               session.controlPause();
            } else {
               session.controlPlay();
            }
            if (vibrate)
               vibrator.vibrate(VIBRATE_LEN);
         }
      });

      this.controlRepeat.setOnClickListener(new OnClickListener() {

         @Override
         public void onClick(View v) {
            checkSetRepeat();
         }

      });
      checkRepeat();

      this.controlShuffle.setOnClickListener(new OnClickListener() {

         @Override
         public void onClick(View v) {
            checkSetShuffle();
         }

      });
      checkShuffle();

      this.fadeview = (FadeView) findViewById(R.id.fadeview);
      this.fadeview.startFade();
      this.fadeview.doubleTapHandler = this.doubleTapHandler;

      this.ratingBar.setOnRatingBarChangeListener(new OnRatingBarChangeListener() {

         public void onRatingChanged(RatingBar ratingBar, float rating, boolean fromUser) {
            if (fromUser && rating <= 5) {
               session.controlRating((long) ((rating / 5) * 100), status.getTrackId());
            }
         }
      });

      int height = getWindowManager().getDefaultDisplay().getHeight();
      int width = getWindowManager().getDefaultDisplay().getWidth();
      int largestDimen = height >= width ? height : width;
      if (largestDimen > 640) {
         largestDimen = 640;
      }
      Status.screenHeight = largestDimen;

      // Speakers adapter needed for the speakers dialog
      speakersAdapter = new SpeakersAdapter(this);
   }

   /**
    * Use to check the repeat status and button state, without altering it
    * unlike {@link checkSetRepeat()}
    */
   protected void checkRepeat() {

      if (session == null || status == null)
         return;

      switch (status.getRepeat()) {

      case Status.REPEAT_OFF: // go to single
         repeat.setLevel(0);
         break;

      case Status.REPEAT_SINGLE: // go to all
         repeat.setLevel(2);
         break;

      case Status.REPEAT_ALL: // go to off
         repeat.setLevel(1);
         break;

      }

   }

   protected void checkSetRepeat() {

      if (session == null || status == null)
         return;

      switch (status.getRepeat()) {

      case Status.REPEAT_OFF: // go to single
         session.controlRepeat(Status.REPEAT_SINGLE);
         repeatToast.setText(R.string.control_menu_repeat_one);
         repeat.setLevel(2);
         break;

      case Status.REPEAT_SINGLE: // go to all
         session.controlRepeat(Status.REPEAT_ALL);
         repeatToast.setText(R.string.control_menu_repeat_all);
         repeat.setLevel(1);
         break;

      case Status.REPEAT_ALL: // go to off
         session.controlRepeat(Status.REPEAT_OFF);
         repeatToast.setText(R.string.control_menu_repeat_none);
         repeat.setLevel(0);
         break;

      }

      if(showToast){
    	  repeatToast.show();
      }

   }

   protected void checkShuffle() {

      if (session == null || status == null)
         return;

      switch (status.getShuffle()) {

      case Status.SHUFFLE_OFF: // Shuffle off
         shuffle.setLevel(0);
         break;

      case Status.SHUFFLE_ON: // Shuffle on
         shuffle.setLevel(1);
         break;

      }

   }

   protected void checkSetShuffle() {

      if (session == null || status == null)
         return;

      switch (status.getShuffle()) {

      case Status.SHUFFLE_OFF: // Turn shuffle on
         session.controlShuffle(Status.SHUFFLE_ON);
         shuffleToast.setText(R.string.control_menu_shuffle_on);
         shuffle.setLevel(1);
         break;

      case Status.SHUFFLE_ON: // Turn shuffle off
         session.controlShuffle(Status.SHUFFLE_OFF);
         shuffleToast.setText(R.string.control_menu_shuffle_off);
         shuffle.setLevel(0);
         break;

      }

      if(showToast){
    	  shuffleToast.show();
      }

   }

   protected void incrementVolume(final long increment) {
      ThreadExecutor.runTask(new Runnable() {
         public void run() {
            checkCachedVolume();

            // increment the volume and send control signal off
            cachedVolume += increment;
            session.controlVolume(ControlActivity.this.cachedVolume);
            // update our volume gui and show
            runOnUiThread(new Runnable() {
               @Override
               public void run() {
                  try {
                     ControlActivity.this.volumeBar.setProgress((int) ControlActivity.this.cachedVolume);
                     ControlActivity.this.volume.invalidate();
                     ControlActivity.this.volumeToast.show();
                  } catch (Exception e) {
                     Log.e(TAG, "Volume Increment Exception:" + e.getMessage());
                  }
               }
            });
         }
      });
   }

   /**
    * Updates the cachedVolume if necessary
    */
   protected void checkCachedVolume() {
      // try assuming a cached volume instead of requesting it each time
      if (System.currentTimeMillis() - cachedTime > CACHE_TIME) {
         if (status == null) {
            return;
         }
         this.cachedVolume = status.getVolume();
         this.cachedTime = System.currentTimeMillis();
      }
   }

   @Override
   public boolean onKeyDown(int keyCode, KeyEvent event) {
      int increment = 5;
      try {
         increment = Integer.parseInt(backend.getPrefs().getString(
                  getResources().getString(R.string.pref_volumeincrement), "5"));

         // check for volume keys
         if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            this.incrementVolume(+increment);
            return true;
         } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            this.incrementVolume(-increment);
            return true;
         }

         // regardless of key, make sure we keep view alive
         this.fadeview.keepAwake();
      } catch (Exception e) {
         Log.e(TAG, "Volume Increment Exception:" + e.getMessage());
      }
      return super.onKeyDown(keyCode, event);
   }

   // get rid of volume rocker default sound effect
   @Override
   public boolean onKeyUp(int keycode, KeyEvent event) {
      switch (keycode) {
      case KeyEvent.KEYCODE_VOLUME_DOWN:
      case KeyEvent.KEYCODE_VOLUME_UP:
         break;
      default:
         return super.onKeyUp(keycode, event);
      }
      return true;
   }

   @Override
   public boolean onCreateOptionsMenu(Menu menu) {
      getMenuInflater().inflate(R.menu.act_control, menu);
      super.onCreateOptionsMenu(menu);
      return true;
   }

   @Override
   public boolean onOptionsItemSelected(MenuItem item) {

      switch (item.getItemId()) {

      case R.id.control_menu_search:
         onSearchRequested();
         return true;

      case R.id.control_menu_artists:
         startActivity(new Intent(this, LibraryBrowseActivity.class));
         return true;

      case R.id.control_menu_speakers:
         showDialog(DIALOG_SPEAKERS);
         return true;

      case R.id.control_menu_add_library:
         startActivity(new Intent(ControlActivity.this, LibraryActivity.class));
         return true;

      case R.id.control_menu_pick_library:
         startActivity(new Intent(ControlActivity.this, ServerActivity.class));
         return true;

      case R.id.control_menu_now_playing:
         StartNowPlaying();
         return true;

      case R.id.control_menu_settings:
         startActivity(new Intent(this, PrefsActivity.class));
         return true;

      case R.id.control_menu_about:
         startActivity(new Intent(this, WizardActivity.class));
         return true;
      }

      return super.onOptionsItemSelected(item);

   }

	@Override
	public void onGesturePerformed(GestureOverlayView overlay, Gesture gesture) {
		ArrayList<Prediction> predictions = gestureLib.recognize(gesture);
		Prediction prediction = predictions.get(0);
		if(session==null){
			return;
		}
		if(predictions.size()>0 && prediction.score > 5.0){
			if(prediction.name.equals("left")){
				if(!invertGestures){
					session.controlNext();
				}
				else{
					session.controlPrev();
				}
			}
			else if(prediction.name.equals("right")){
				if(!invertGestures){
					session.controlPrev();
				}
				else{
					session.controlNext();
				}
			}
		}		
	}

}
