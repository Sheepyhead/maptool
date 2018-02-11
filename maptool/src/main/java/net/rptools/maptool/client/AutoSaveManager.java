/*
 * This software copyright by various authors including the RPTools.net
 * development team, and licensed under the LGPL Version 3 or, at your option,
 * any later version.
 *
 * Portions of this software were originally covered under the Apache Software
 * License, Version 1.1 or Version 2.0.
 *
 * See the file LICENSE elsewhere in this distribution for license details.
 */

package net.rptools.maptool.client;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.Timer;

import org.apache.log4j.Logger;

import net.rptools.maptool.language.I18N;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.util.PersistenceUtil;

/**
 * @author tylere
 *
 *         Attempts to recover campaigns when the application crashes.
 */
public class AutoSaveManager implements ActionListener {
	private static final Logger log = Logger.getLogger(AutoSaveManager.class);
	private Timer autoSaveTimer;
	public static final File AUTOSAVE_FILE = new File(AppUtil.getAppHome("autosave"), "AutoSave" + AppConstants.CAMPAIGN_FILE_EXTENSION); //$NON-NLS-1$

	public void start() {
		restart();
	}

	/**
	 * Queries the auto-save increment from {@link AppPreferences} and starts a
	 * new timer.
	 */
	public void restart() {
		int interval = AppPreferences.getAutoSaveIncrement();

		//convert to milliseconds
		int delay = interval * 60 * 1000;
		if (log.isDebugEnabled())
			log.debug("Starting autosave manager; interval in seconds is " + (interval * 60)); //$NON-NLS-1$

		if (autoSaveTimer == null) {
			if (interval <= 0) { // auto-save is turned off with <= 0
				return;
			} else {
				if (log.isDebugEnabled())
					log.debug("Autosave timer doesn't exist; creating new one.  Delay(ms) = " + delay); //$NON-NLS-1$
				autoSaveTimer = new Timer(delay, this);
				autoSaveTimer.start(); // Start it running...
			}
		} else {
			if (interval <= 0) {
				if (log.isDebugEnabled())
					log.debug("Autosave timer exists; stop it and remove it."); //$NON-NLS-1$
				autoSaveTimer.stop(); // auto-save is off; stop the Timer first
				autoSaveTimer = null;
			} else {
				if (log.isDebugEnabled())
					log.debug("Autosave timer exists; set delay and restart.  Delay(ms) = " + delay); //$NON-NLS-1$
				autoSaveTimer.setInitialDelay(delay);
				autoSaveTimer.setDelay(delay);
				autoSaveTimer.restart(); // Set the new delay and restart the Timer
			}
		}
	}

	/**
	 * Applications can use this to pause the timer. The {@link #restart()}
	 * method can be called at any time to reset and start the timer.
	 */
	public void pause() {
		if (autoSaveTimer != null && autoSaveTimer.isRunning()) {
			if (log.isDebugEnabled())
				log.debug("Stopping autosave timer..."); //$NON-NLS-1$
			autoSaveTimer.stop();
		}
	}

	public void actionPerformed(ActionEvent e) {
		// Don't autosave if we don't "own" the campaign
		if (!MapTool.isHostingServer() && !MapTool.isPersonalServer()) {
			return;
		}
		try {
			MapTool.getFrame().setStatusMessage(I18N.getString("AutoSaveManager.status.autoSaving"));
			long startCopy = System.currentTimeMillis();

			// This occurs on the event dispatch thread, so it's ok to mess with the models.
			// We need to clone the campaign so that we can save in the background, but
			// not have concurrency issues with the original model.
			//
			// NOTE: This is a cheesy way to clone the campaign, but it makes it so that I
			// don't have to keep all the various models' clone methods updated on each change.
			final Campaign campaign = new Campaign(MapTool.getCampaign());
			if (log.isInfoEnabled())
				log.info("Time to copy Campaign object (ms): " + (System.currentTimeMillis() - startCopy)); //$NON-NLS-1$

			// Now that we have a copy of the model, save that one
			// TODO: Replace this with a swing worker
			new Thread(null, new Runnable() {
				public void run() {
					if (log.isDebugEnabled())
						log.debug("Beginning autosave process..."); //$NON-NLS-1$
					synchronized (AppState.class) {
						if (AppState.isSaving()) {
							// Can't autosave right now because the user is manually saving.
							// The manual save will remove existing autosaves (?) so there's nothing
							// for us to do.  Just return.
							if (log.isDebugEnabled())
								log.debug("Canceling autosave because user has initiated save operation"); //$NON-NLS-1$
							return;
						}
						AppState.setIsSaving(true);
						pause();
					}
					long startSave = System.currentTimeMillis();
					try {
						if (log.isDebugEnabled())
							log.debug("AppState.isSaving() is true; writing file..."); //$NON-NLS-1$
						PersistenceUtil.saveCampaign(campaign, AUTOSAVE_FILE, null);
						long totalTime = System.currentTimeMillis() - startSave;
						if (log.isDebugEnabled())
							log.debug("File IO complete; time required(ms): " + totalTime); //$NON-NLS-1$
						MapTool.getFrame().setStatusMessage(I18N.getText("AutoSaveManager.status.autoSaveComplete", totalTime));
					} catch (Throwable ioe) {
						if (log.isDebugEnabled())
							log.debug("Exception occurred: " + ioe); //$NON-NLS-1$
						MapTool.showError("AutoSaveManager.failed", ioe);
					} finally {
						if (log.isDebugEnabled())
							log.debug("Resetting AppState.isSaving() to false..."); //$NON-NLS-1$
						synchronized (AppState.class) {
							AppState.setIsSaving(false);
							restart();
						}
						if (log.isDebugEnabled())
							log.debug("Autosave complete."); //$NON-NLS-1$
					}
				}
			}, "AutoSaveThread").start();
		} catch (Throwable t) {
			/*
			 * If this routine fails, be sure the isSaving flag is turned off.
			 * This should not be necessary: If the exception occurs anywhere
			 * before the .start() method of Thread, the boolean does not need
			 * to be reset. And if the .start() method is successful, this code
			 * will never be invoked (exceptions thrown in the thread will not
			 * propagate back to here), in which case the .run() method will
			 * decide when to set/reset the flag. For safety's sake I retrieve
			 * the current value and report it if it's true, but we shouldn't be
			 * able to get here in that case...
			 */
			synchronized (AppState.class) {
				if (AppState.isSaving()) {
					MapTool.showError(I18N.getString("AutoSaveManager.failed") + "<br/>\nand AppState.isSaving() is true!", t);
					AppState.setIsSaving(false);
				} else {
					MapTool.showError("AutoSaveManager.failed", t);
				}
			}
		}
	}

	/**
	 * Removes any autosaved files
	 */
	public void purge() {
		if (AUTOSAVE_FILE.exists()) {
			AUTOSAVE_FILE.delete();
		}
	}

	/**
	 * Removes the campaignFile if it's from Autosave, forcing to save as new
	 */
	public void tidy() {
		if (AUTOSAVE_FILE.equals(AppState.getCampaignFile())) {
			AppState.setCampaignFile(null);
		}
		purge();
	}

	/**
	 * Check to see if autosave recovery is necessary.
	 */
	public void check() {
		if (AUTOSAVE_FILE.exists()) {
			boolean okay;
			okay = MapTool.confirm("msg.confirm.recoverAutosave", AUTOSAVE_FILE.lastModified());
			if (okay)
				AppActions.loadCampaign(AUTOSAVE_FILE);
		}
	}
}
