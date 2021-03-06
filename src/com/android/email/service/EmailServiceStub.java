/* Copyright (C) 2012 The Android Open Source Project
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

package com.android.email.service;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.email.NotificationController;
import com.android.email.Preferences;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.AccountReconciler;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeHeader;
import com.android.emailcommon.internet.MimeMultipart;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.FetchProfile;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Folder.MessageRetrievalListener;
import com.android.emailcommon.mail.Folder.OpenMode;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

import java.util.HashSet;

/**
 * EmailServiceStub is an abstract class representing an EmailService
 *
 * This class provides legacy support for a few methods that are common to both
 * IMAP and POP3, including startSync, loadMore, loadAttachment, and sendMail
 */
public abstract class EmailServiceStub extends IEmailService.Stub implements IEmailService {

    private static final int MAILBOX_COLUMN_ID = 0;
    private static final int MAILBOX_COLUMN_SERVER_ID = 1;
    private static final int MAILBOX_COLUMN_TYPE = 2;

    /** Small projection for just the columns required for a sync. */
    private static final String[] MAILBOX_PROJECTION = new String[] {
        MailboxColumns.ID,
        MailboxColumns.SERVER_ID,
        MailboxColumns.TYPE,
    };

    protected Context mContext;

    protected void init(Context context) {
        mContext = context;
    }

    @Override
    public Bundle validate(HostAuth hostauth) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    protected void requestSync(long mailboxId, boolean userRequest, int deltaMessageCount) {
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
        if (mailbox == null) return;
        final Account account = Account.restoreAccountWithId(mContext, mailbox.mAccountKey);
        if (account == null) return;
        final EmailServiceInfo info =
                EmailServiceUtils.getServiceInfoForAccount(mContext, account.mId);
        final android.accounts.Account acct = new android.accounts.Account(account.mEmailAddress,
                info.accountType);
        final Bundle extras = Mailbox.createSyncBundle(mailboxId);
        if (userRequest) {
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        }
        if (deltaMessageCount != 0) {
            extras.putInt(Mailbox.SYNC_EXTRA_DELTA_MESSAGE_COUNT, deltaMessageCount);
        }
        ContentResolver.requestSync(acct, EmailContent.AUTHORITY, extras);
        LogUtils.i(Logging.LOG_TAG, "requestSync EmailServiceStub startSync %s, %s",
                account.toString(), extras.toString());
    }

    /**
     * Delete a single message by moving it to the trash, or really delete it if it's already in
     * trash or a draft message. This function has no callback, no result reporting, because the
     * desired outcome is reflected entirely by changes to one or more cursors.
     *
     * @param messageId The id of the message to "delete".
     */
    public void deleteMessage(long messageId) {
        final EmailContent.Message message =
                EmailContent.Message.restoreMessageWithId(mContext, messageId);
        if (message == null) {
            if (Logging.LOGD) LogUtils.v(Logging.LOG_TAG, "dletMsg message NULL");
            return;
        }

        // 1. Get the message's account
        final Account account = Account.restoreAccountWithId(mContext, message.mAccountKey);
        // 2. Get the message's original mailbox
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
        if (account == null || mailbox == null) {
            if (Logging.LOGD) LogUtils.v(Logging.LOG_TAG, "dletMsg account or mailbox NULL");
            return;
        }
        if (Logging.LOGD) {
            LogUtils.d(Logging.LOG_TAG, "AccountKey " + account.mId + "oirigMailbix: "
                    + mailbox.mId);
        }

        // 3. Confirm that there is a trash mailbox available. If not, create one
        Mailbox trashFolder = Mailbox.restoreMailboxOfType(mContext, account.mId,
                Mailbox.TYPE_TRASH);
        if (trashFolder == null) {
            if (Logging.LOGD) LogUtils.v(Logging.LOG_TAG, "dletMsg Trash mailbox NULL");
        } else {
            LogUtils.d(Logging.LOG_TAG, "TrasMailbix: " + trashFolder.mId);
        }

        // 4. Drop non-essential data for the message (e.g. attachment files)
        AttachmentUtilities.deleteAllAttachmentFiles(mContext, account.mId,
                messageId);

        // 5. Perform "delete" as appropriate
        Uri uri = ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI,
                messageId);
        if ((mailbox.mId == trashFolder.mId) || (mailbox.mType == Mailbox.TYPE_DRAFTS)) {
            // 5a. Really delete it
            mContext.getContentResolver().delete(uri, null, null);
        } else {
            // 5b. Move to trash
            ContentValues cv = new ContentValues();
            cv.put(EmailContent.MessageColumns.MAILBOX_KEY, trashFolder.mId);
            mContext.getContentResolver().update(uri, cv, null, null);
        }

        requestSync(mailbox.mId, true, 0);
    }

    /**
     * Moves messages to a new mailbox. This function has no callback, no result reporting, because
     * the desired outcome is reflected entirely by changes to one or more cursors. Note this method
     * assumes all of the given message and mailbox IDs belong to the same account.
     *
     * @param messageIds IDs of the messages that are to be moved
     * @param newMailboxId ID of the new mailbox that the messages will be moved to
     * @return an asynchronous task that executes the move (for testing only)
     */
    public void MoveMessages(long messageId, long newMailboxId) {
        Account account = Account.getAccountForMessageId(mContext, messageId);
        if (account != null) {
            if (Logging.LOGD) {
                LogUtils.d(Logging.LOG_TAG, "moveMessage Acct " + account.mId + "messageId:"
                        + messageId);
            }
            ContentValues cv = new ContentValues();
            cv.put(EmailContent.MessageColumns.MAILBOX_KEY, newMailboxId);
            ContentResolver resolver = mContext.getContentResolver();
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Message.SYNCED_CONTENT_URI, messageId);
            resolver.update(uri, cv, null, null);
        } else {
            LogUtils.d(Logging.LOG_TAG, "moveMessage Cannot find account");
        }
    }

    /**
     * Set/clear boolean columns of a message
     *
     * @param messageId the message to update
     * @param columnName the column to update
     * @param columnValue the new value for the column
     */
    private void setMessageBoolean(long messageId, String columnName, boolean columnValue) {
        ContentValues cv = new ContentValues();
        cv.put(columnName, columnValue);
        Uri uri = ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        mContext.getContentResolver().update(uri, cv, null, null);
    }

    /**
     * Set/clear the unread status of a message
     *
     * @param messageId the message to update
     * @param isRead the new value for the isRead flag
     */
    public void setMessageRead(long messageId, boolean isRead) {
        setMessageBoolean(messageId, EmailContent.MessageColumns.FLAG_READ, isRead);
    }

    @Override
    public void loadAttachment(final IEmailServiceCallback cb, final long accountId,
            final long attachmentId, final boolean background) throws RemoteException {
        Folder remoteFolder = null;
        try {
            //1. Check if the attachment is already here and return early in that case
            Attachment attachment =
                Attachment.restoreAttachmentWithId(mContext, attachmentId);
            if (attachment == null) {
                cb.loadAttachmentStatus(0, attachmentId,
                        EmailServiceStatus.ATTACHMENT_NOT_FOUND, 0);
                return;
            }
            final long messageId = attachment.mMessageKey;

            final EmailContent.Message message =
                    EmailContent.Message.restoreMessageWithId(mContext, attachment.mMessageKey);
            if (message == null) {
                cb.loadAttachmentStatus(messageId, attachmentId,
                        EmailServiceStatus.MESSAGE_NOT_FOUND, 0);
                return;
            }

            // If the message is loaded, just report that we're finished
            if (Utility.attachmentExists(mContext, attachment)
                    && attachment.mUiState == UIProvider.AttachmentState.SAVED) {
                cb.loadAttachmentStatus(messageId, attachmentId, EmailServiceStatus.SUCCESS,
                        0);
                return;
            }

            // Say we're starting...
            cb.loadAttachmentStatus(messageId, attachmentId, EmailServiceStatus.IN_PROGRESS, 0);

            // 2. Open the remote folder.
            final Account account = Account.restoreAccountWithId(mContext, message.mAccountKey);
            Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);

            if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
                long sourceId = Utility.getFirstRowLong(mContext, Body.CONTENT_URI,
                        new String[] {BodyColumns.SOURCE_MESSAGE_KEY},
                        BodyColumns.MESSAGE_KEY + "=?",
                        new String[] {Long.toString(messageId)}, null, 0, -1L);
                if (sourceId != -1) {
                    EmailContent.Message sourceMsg =
                            EmailContent.Message.restoreMessageWithId(mContext, sourceId);
                    if (sourceMsg != null) {
                        mailbox = Mailbox.restoreMailboxWithId(mContext, sourceMsg.mMailboxKey);
                        message.mServerId = sourceMsg.mServerId;
                    }
                }
            } else if (mailbox.mType == Mailbox.TYPE_SEARCH && message.mMainMailboxKey != 0) {
                mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMainMailboxKey);
            }

            if (account == null || mailbox == null) {
                // If the account/mailbox are gone, just report success; the UI handles this
                cb.loadAttachmentStatus(messageId, attachmentId,
                        EmailServiceStatus.SUCCESS, 0);
                return;
            }
            TrafficStats.setThreadStatsTag(
                    TrafficFlags.getAttachmentFlags(mContext, account));

            final Store remoteStore = Store.getInstance(account, mContext);
            remoteFolder = remoteStore.getFolder(mailbox.mServerId);
            remoteFolder.open(OpenMode.READ_WRITE);

            // 3. Generate a shell message in which to retrieve the attachment,
            // and a shell BodyPart for the attachment.  Then glue them together.
            final Message storeMessage = remoteFolder.createMessage(message.mServerId);
            final MimeBodyPart storePart = new MimeBodyPart();
            storePart.setSize((int)attachment.mSize);
            storePart.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA,
                    attachment.mLocation);
            storePart.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                    String.format("%s;\n name=\"%s\"",
                    attachment.mMimeType,
                    attachment.mFileName));

            // TODO is this always true for attachments?  I think we dropped the
            // true encoding along the way
            storePart.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");

            final MimeMultipart multipart = new MimeMultipart();
            multipart.setSubType("mixed");
            multipart.addBodyPart(storePart);

            storeMessage.setHeader(MimeHeader.HEADER_CONTENT_TYPE, "multipart/mixed");
            storeMessage.setBody(multipart);

            // 4. Now ask for the attachment to be fetched
            final FetchProfile fp = new FetchProfile();
            fp.add(storePart);
            remoteFolder.fetch(new Message[] { storeMessage }, fp,
                    new MessageRetrievalListenerBridge(messageId, attachmentId, cb));

            // If we failed to load the attachment, throw an Exception here, so that
            // AttachmentDownloadService knows that we failed
            if (storePart.getBody() == null) {
                throw new MessagingException("Attachment not loaded.");
            }

            // Save the attachment to wherever it's going
            AttachmentUtilities.saveAttachment(mContext, storePart.getBody().getInputStream(),
                    attachment);

            // 6. Report success
            cb.loadAttachmentStatus(messageId, attachmentId, EmailServiceStatus.SUCCESS, 0);

        } catch (MessagingException me) {
            LogUtils.i(Logging.LOG_TAG, me, "Error loading attachment");

            final ContentValues cv = new ContentValues(1);
            cv.put(AttachmentColumns.UI_STATE, UIProvider.AttachmentState.FAILED);
            final Uri uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, attachmentId);
            mContext.getContentResolver().update(uri, cv, null, null);

            cb.loadAttachmentStatus(0, attachmentId, EmailServiceStatus.CONNECTION_ERROR, 0);
        } finally {
            if (remoteFolder != null) {
                remoteFolder.close(false);
            }
        }

    }

    /**
     * Bridge to intercept {@link MessageRetrievalListener#loadAttachmentProgress} and
     * pass down to {@link IEmailServiceCallback}.
     */
    public class MessageRetrievalListenerBridge implements MessageRetrievalListener {
        private final long mMessageId;
        private final long mAttachmentId;
        private final IEmailServiceCallback mCallback;


        public MessageRetrievalListenerBridge(final long messageId, final long attachmentId,
                final IEmailServiceCallback callback) {
            mMessageId = messageId;
            mAttachmentId = attachmentId;
            mCallback = callback;
        }

        @Override
        public void loadAttachmentProgress(int progress) {
            try {
                mCallback.loadAttachmentStatus(mMessageId, mAttachmentId,
                        EmailServiceStatus.IN_PROGRESS, progress);
            } catch (final RemoteException e) {
                // No danger if the client is no longer around
            }
        }

        @Override
        public void messageRetrieved(com.android.emailcommon.mail.Message message) {
        }
    }

    @Override
    public void updateFolderList(long accountId) throws RemoteException {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;
        long inboxId = -1;
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, account));
        Cursor localFolderCursor = null;
        try {
            // Step 0: Make sure the default system mailboxes exist.
            for (final int type : Mailbox.REQUIRED_FOLDER_TYPES) {
                if (Mailbox.findMailboxOfType(mContext, accountId, type) == Mailbox.NO_MAILBOX) {
                    final Mailbox mailbox = Mailbox.newSystemMailbox(mContext, accountId, type);
                    mailbox.save(mContext, Preferences.getPreferences(mContext).getEnableBypassPolicyRequirements());
                    if (type == Mailbox.TYPE_INBOX) {
                        inboxId = mailbox.mId;
                    }
                }
            }

            // Step 1: Get remote mailboxes
            final Store store = Store.getInstance(account, mContext);
            final Folder[] remoteFolders = store.updateFolders();
            final HashSet<String> remoteFolderNames = new HashSet<String>();
            for (final Folder remoteFolder : remoteFolders) {
                remoteFolderNames.add(remoteFolder.getName());
            }

            // Step 2: Get local mailboxes
            localFolderCursor = mContext.getContentResolver().query(
                    Mailbox.CONTENT_URI,
                    MAILBOX_PROJECTION,
                    EmailContent.MailboxColumns.ACCOUNT_KEY + "=?",
                    new String[] { String.valueOf(account.mId) },
                    null);

            // Step 3: Remove any local mailbox not on the remote list
            while (localFolderCursor.moveToNext()) {
                final String mailboxPath = localFolderCursor.getString(MAILBOX_COLUMN_SERVER_ID);
                // Short circuit if we have a remote mailbox with the same name
                if (remoteFolderNames.contains(mailboxPath)) {
                    continue;
                }

                final int mailboxType = localFolderCursor.getInt(MAILBOX_COLUMN_TYPE);
                final long mailboxId = localFolderCursor.getLong(MAILBOX_COLUMN_ID);
                switch (mailboxType) {
                    case Mailbox.TYPE_INBOX:
                    case Mailbox.TYPE_DRAFTS:
                    case Mailbox.TYPE_OUTBOX:
                    case Mailbox.TYPE_SENT:
                    case Mailbox.TYPE_TRASH:
                    case Mailbox.TYPE_SEARCH:
                        // Never, ever delete special mailboxes
                        break;
                    default:
                        // Drop all attachment files related to this mailbox
                        AttachmentUtilities.deleteAllMailboxAttachmentFiles(
                                mContext, accountId, mailboxId);
                        // Delete the mailbox; database triggers take care of related
                        // Message, Body and Attachment records
                        Uri uri = ContentUris.withAppendedId(
                                Mailbox.CONTENT_URI, mailboxId);
                        mContext.getContentResolver().delete(uri, null, null);
                        break;
                }
            }
        } catch (MessagingException me) {
            LogUtils.i(Logging.LOG_TAG, me, "Error in updateFolderList");
            // We'll hope this is temporary
        } finally {
            if (localFolderCursor != null) {
                localFolderCursor.close();
            }
            // If we just created the inbox, sync it
            if (inboxId != -1) {
                requestSync(inboxId, true, 0);
            }
        }
    }

    @Override
    public void setServiceBitfields(int bitfield) throws RemoteException {
        // Not required
    }

    @Override
    public Bundle autoDiscover(String userName, String password) throws RemoteException {
        // Not required
       return null;
    }

    @Override
    public void sendMeetingResponse(long messageId, int response) throws RemoteException {
        // Not required
    }

    @Override
    public void deleteAccountPIMData(final String emailAddress) throws RemoteException {
        AccountReconciler.reconcileAccounts(mContext);
    }

    @Override
    public int searchMessages(long accountId, SearchParams params, long destMailboxId)
            throws RemoteException {
        // Not required
        return 0;
    }

    @Override
    public void pushModify(long accountId) throws RemoteException {
        LogUtils.e(Logging.LOG_TAG, "pushModify invalid for account type for %d", accountId);
    }

    @Override
    public void sync(final long accountId, final boolean updateFolderList,
            final int mailboxType, final long[] folders) {}

    @Override
    public void sendMail(long accountId) throws RemoteException {
        sendMailImpl(mContext, accountId);
    }

    public static void sendMailImpl(Context context, long accountId) {
        final Account account = Account.restoreAccountWithId(context, accountId);
        TrafficStats.setThreadStatsTag(TrafficFlags.getSmtpFlags(context, account));
        final NotificationController nc = NotificationController.getInstance(context);
        // 1.  Loop through all messages in the account's outbox
        final long outboxId = Mailbox.findMailboxOfType(context, account.mId, Mailbox.TYPE_OUTBOX);
        if (outboxId == Mailbox.NO_MAILBOX) {
            return;
        }
        final ContentResolver resolver = context.getContentResolver();
        final Cursor c = resolver.query(EmailContent.Message.CONTENT_URI,
                EmailContent.Message.ID_COLUMN_PROJECTION,
                EmailContent.Message.MAILBOX_KEY + "=?", new String[] { Long.toString(outboxId) },
                null);
        try {
            // 2.  exit early
            if (c.getCount() <= 0) {
                return;
            }
            final Sender sender = Sender.getInstance(context, account);
            final Store remoteStore = Store.getInstance(account, context);
            final ContentValues moveToSentValues;
            if (remoteStore.requireCopyMessageToSentFolder()) {
                Mailbox sentFolder =
                    Mailbox.restoreMailboxOfType(context, accountId, Mailbox.TYPE_SENT);
                moveToSentValues = new ContentValues();
                moveToSentValues.put(MessageColumns.MAILBOX_KEY, sentFolder.mId);
            } else {
                moveToSentValues = null;
            }

            // 3.  loop through the available messages and send them
            while (c.moveToNext()) {
                final long messageId;
                if (moveToSentValues != null) {
                    moveToSentValues.remove(EmailContent.MessageColumns.FLAGS);
                }
                try {
                    messageId = c.getLong(0);
                    // Don't send messages with unloaded attachments
                    if (Utility.hasUnloadedAttachments(context, messageId)) {
                        if (MailActivityEmail.DEBUG) {
                            LogUtils.d(Logging.LOG_TAG, "Can't send #" + messageId +
                                    "; unloaded attachments");
                        }
                        continue;
                    }
                    sender.sendMessage(messageId);
                } catch (MessagingException me) {
                    // report error for this message, but keep trying others
                    if (me instanceof AuthenticationFailedException) {
                        nc.showLoginFailedNotification(account.mId);
                    }
                    continue;
                }
                // 4. move to sent, or delete
                final Uri syncedUri =
                    ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);
                // Delete all cached files
                AttachmentUtilities.deleteAllCachedAttachmentFiles(context, account.mId, messageId);
                if (moveToSentValues != null) {
                    // If this is a forwarded message and it has attachments, delete them, as they
                    // duplicate information found elsewhere (on the server).  This saves storage.
                    final EmailContent.Message msg =
                        EmailContent.Message.restoreMessageWithId(context, messageId);
                    if ((msg.mFlags & EmailContent.Message.FLAG_TYPE_FORWARD) != 0) {
                        AttachmentUtilities.deleteAllAttachmentFiles(context, account.mId,
                                messageId);
                    }
                    final int flags = msg.mFlags & ~(EmailContent.Message.FLAG_TYPE_REPLY |
                            EmailContent.Message.FLAG_TYPE_FORWARD |
                            EmailContent.Message.FLAG_TYPE_REPLY_ALL |
                            EmailContent.Message.FLAG_TYPE_ORIGINAL);

                    moveToSentValues.put(EmailContent.MessageColumns.FLAGS, flags);
                    resolver.update(syncedUri, moveToSentValues, null, null);
                } else {
                    AttachmentUtilities.deleteAllAttachmentFiles(context, account.mId,
                            messageId);
                    final Uri uri =
                        ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, messageId);
                    resolver.delete(uri, null, null);
                    resolver.delete(syncedUri, null, null);
                }
            }
            nc.cancelLoginFailedNotification(account.mId);
        } catch (MessagingException me) {
            if (me instanceof AuthenticationFailedException) {
                nc.showLoginFailedNotification(account.mId);
            }
        } finally {
            c.close();
        }

    }
}
