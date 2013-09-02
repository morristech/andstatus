package org.andstatus.app.net;

import android.net.Uri;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;

import org.andstatus.app.origin.OriginConnectionData;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.SharedPreferencesUtil;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * Twitter API implementations
 * @author yvolk
 */
public abstract class ConnectionTwitter extends Connection {
    private static final String TAG = ConnectionTwitter.class.getSimpleName();

    protected static Connection fromConnectionDataProtected(OriginConnectionData connectionData) {
        Connection connection;
        switch (connectionData.api) {
            case STATUSNET_TWITTER:
                connection = new ConnectionTwitterStatusNet(connectionData);
                break;
            case TWITTER1P0:
                connection = new ConnectionTwitter1p0(connectionData);
                break;
            default:
                connection = new ConnectionTwitter1p1(connectionData);
        }
        return connection;
    }

    public ConnectionTwitter(OriginConnectionData connectionData) {
        super(connectionData);
    }

    /**
     * URL of the API. Not logged
     * @param routine
     * @return URL or an empty string in a case the API routine is not supported
     */
    @Override
    protected String getApiPath1(ApiRoutineEnum routine) {
        String url;
        switch(routine) {
            case ACCOUNT_RATE_LIMIT_STATUS:
                url = "account/rate_limit_status" + EXTENSION;
                break;
            case ACCOUNT_VERIFY_CREDENTIALS:
                url = "account/verify_credentials" + EXTENSION;
                break;
            case DIRECT_MESSAGES:
                url = "direct_messages" + EXTENSION;
                break;
            case FAVORITES_CREATE_BASE:
                url = "favorites/create/";
                break;
            case FAVORITES_DESTROY_BASE:
                url = "favorites/destroy/";
                break;
            case FOLLOW_USER:
                url = "friendships/create" + EXTENSION;
                break;
            case GET_FRIENDS_IDS:
                url = "friends/ids" + EXTENSION;
                break;
            case GET_USER:
                url = "users/show" + EXTENSION;
                break;
            case POST_DIRECT_MESSAGE:
                url = "direct_messages/new" + EXTENSION;
                break;
            case POST_REBLOG:
                url = "statuses/retweet/";
                break;
            case STATUSES_DESTROY:
                url = "statuses/destroy/";
                break;
            case STATUSES_HOME_TIMELINE:
                url = "statuses/home_timeline" + EXTENSION;
                break;
            case STATUSES_MENTIONS_TIMELINE:
                url = "statuses/mentions" + EXTENSION;
                break;
            case STATUSES_USER_TIMELINE:
                url = "statuses/user_timeline" + EXTENSION;
                break;
            case STATUSES_SHOW:
                url = "statuses/show" + EXTENSION;
                break;
            case STATUSES_UPDATE:
                url = "statuses/update" + EXTENSION;
                break;
            case STOP_FOLLOWING_USER:
                url = "friendships/destroy" + EXTENSION;
                break;
            default:
                url = "";
        }
        if (!TextUtils.isEmpty(url)) {
            url = httpConnection.connectionData.basicPath + "/" + url;
        }
        return url;
    }

    @Override
    public boolean destroyStatus(String statusId) throws ConnectionException {
        JSONObject jso = httpConnection.postRequest(getApiPath(ApiRoutineEnum.STATUSES_DESTROY) + statusId + EXTENSION);
        if (jso != null && MyLog.isLoggable(null, Log.VERBOSE)) {
            try {
                Log.v(TAG, "destroyStatus response: " + jso.toString(2));
            } catch (JSONException e) {
                e.printStackTrace();
                jso = null;
            }
        }
        return (jso != null);
    }
    
    /**
     * @see <a
     *      href="https://dev.twitter.com/docs/api/1.1/post/friendships/create">POST friendships/create</a>
     * @see <a
     *      href="https://dev.twitter.com/docs/api/1.1/post/friendships/destroy">POST friendships/destroy</a>
     */
    @Override
    public MbUser followUser(String userId, Boolean follow) throws ConnectionException {
        List<NameValuePair> out = new LinkedList<NameValuePair>();
        out.add(new BasicNameValuePair("user_id", userId));
        JSONObject user = postRequest((follow ? ApiRoutineEnum.FOLLOW_USER : ApiRoutineEnum.STOP_FOLLOWING_USER), out);
        return userFromJson(user);
    } 

    /**
     * Returns an array of numeric IDs for every user the specified user is following.
     * Current implementation is restricted to 5000 IDs (no paged cursors are used...)
     * @see <a
     *      href="https://dev.twitter.com/docs/api/1.1/get/friends/ids">GET friends/ids</a>
     * @throws ConnectionException
     */
    @Override
    public List<String> getIdsOfUsersFollowedBy(String userId) throws ConnectionException {
        Uri sUri = Uri.parse(getApiPath(ApiRoutineEnum.GET_FRIENDS_IDS));
        Uri.Builder builder = sUri.buildUpon();
        builder.appendQueryParameter("user_id", userId);
        JSONObject jso = httpConnection.getRequest(builder.build().toString());
        List<String> list = new ArrayList<String>();
        if (jso != null) {
            try {
                JSONArray jArr = jso.getJSONArray("ids");
                for (int index = 0; index < jArr.length(); index++) {
                    list.add(jArr.getString(index));
                }
            } catch (JSONException e) {
                throw ConnectionException.loggedJsonException(TAG, e, jso, "Parsing friendsIds");
            }
        }
        return list;
    }
    
    /**
     * Returns a single status, specified by the id parameter below.
     * The status's author will be returned inline.
     * @see <a
     *      href="https://dev.twitter.com/docs/api/1/get/statuses/show/%3Aid">Twitter
     *      REST API Method: statuses/destroy</a>
     * 
     * @throws ConnectionException
     */
    @Override
    public MbMessage getStatus(String statusId) throws ConnectionException {
        Uri sUri = Uri.parse(getApiPath(ApiRoutineEnum.STATUSES_SHOW));
        Uri.Builder builder = sUri.buildUpon();
        builder.appendQueryParameter("id", statusId);
        JSONObject message = httpConnection.getRequest(builder.build().toString());
        return messageFromJson(message);
    }

    @Override
    public List<MbMessage> getTimeline(ApiRoutineEnum apiRoutine, TimelinePosition sinceId, int limit, String userId)
            throws ConnectionException {
        String url = this.getApiPath(apiRoutine);
        Uri sUri = Uri.parse(url);
        Uri.Builder builder = sUri.buildUpon();
        if (!sinceId.isEmpty()) {
            builder.appendQueryParameter("since_id", sinceId.getPosition());
        }
        if (fixedLimit(limit) > 0) {
            builder.appendQueryParameter("count", String.valueOf(fixedLimit(limit)));
        }
        if (!TextUtils.isEmpty(userId)) {
            builder.appendQueryParameter("user_id", userId);
        }
        JSONArray jArr = httpConnection.getRequestAsArray(builder.build().toString());
        List<MbMessage> timeline = new ArrayList<MbMessage>();
        if (jArr != null) {
            for (int index = 0; index < jArr.length(); index++) {
                try {
                    JSONObject jso = jArr.getJSONObject(index);
                    MbMessage mbMessage = messageFromJson(jso);
                    if (!mbMessage.isEmpty()) {
                        timeline.add(mbMessage);
                    }
                } catch (JSONException e) {
                    throw ConnectionException.loggedJsonException(TAG, e, null, "Parsing timeline");
                }
            }
        }
        MyLog.d(TAG, "getTimeline '" + url + "' " + timeline.size() + " messages");
        return timeline;
    }

    protected MbMessage messageFromJson(JSONObject jso) throws ConnectionException {
        if (jso == null) {
            return MbMessage.getEmpty();
        }
        String oid = jso.optString("id_str");
        if (TextUtils.isEmpty(oid)) {
            // This is for the Status.net
            oid = jso.optString("id");
        } 
        MbMessage message =  MbMessage.fromOriginAndOid(httpConnection.connectionData.originId, oid);
        message.reader = MbUser.fromOriginAndUserName(httpConnection.connectionData.originId, httpConnection.accountUsername);
        try {
            if (jso.has("created_at")) {
                Long created = 0L;
                String createdAt = jso.getString("created_at");
                if (createdAt.length() > 0) {
                    created = Date.parse(createdAt);
                }
                if (created > 0) {
                    message.sentDate = created;
                }
            }

            JSONObject sender;
            if (jso.has("sender")) {
                sender = jso.getJSONObject("sender");
                message.sender = userFromJson(sender);
            } else if (jso.has("user")) {
                sender = jso.getJSONObject("user");
                message.sender = userFromJson(sender);
            }
            
            // Is this a reblog?
            if (jso.has("retweeted_status")) {
                JSONObject rebloggedMessage = jso.getJSONObject("retweeted_status");
                message.rebloggedMessage = messageFromJson(rebloggedMessage);
            }
            if (jso.has("text")) {
                message.body = Html.fromHtml(jso.getString("text")).toString();
            }

            if (jso.has("recipient")) {
                JSONObject recipient = jso.getJSONObject("recipient");
                message.recipient = userFromJson(recipient);
            }
            if (jso.has("source")) {
                message.via = jso.getString("source");
            }
            if (jso.has("favorited")) {
                message.favoritedByReader = SharedPreferencesUtil.isTrue(jso.getString("favorited"));
            }

            // If the Msg is a Reply to other message
            String inReplyToUserOid = "";
            String inReplyToUserName = "";
            String inReplyToMessageOid = "";
            if (jso.has("in_reply_to_user_id_str")) {
                inReplyToUserOid = jso.getString("in_reply_to_user_id_str");
            } else if (jso.has("in_reply_to_user_id")) {
                // This is for Status.net
                inReplyToUserOid = jso.getString("in_reply_to_user_id");
            }
            if (SharedPreferencesUtil.isEmpty(inReplyToUserOid)) {
                inReplyToUserOid = "";
            }
            if (!SharedPreferencesUtil.isEmpty(inReplyToUserOid)) {
                if (jso.has("in_reply_to_screen_name")) {
                    inReplyToUserName = jso.getString("in_reply_to_screen_name");
                }
                // Construct "User" from available info
                JSONObject inReplyToUser = new JSONObject();
                inReplyToUser.put("id_str", inReplyToUserOid);
                inReplyToUser.put("screen_name", inReplyToUserName);
                if (jso.has("in_reply_to_status_id_str")) {
                    inReplyToMessageOid = jso.getString("in_reply_to_status_id_str");
                } else if (jso.has("in_reply_to_status_id")) {
                    // This is for identi.ca
                    inReplyToMessageOid = jso.getString("in_reply_to_status_id");
                }
                if (SharedPreferencesUtil.isEmpty(inReplyToMessageOid)) {
                    inReplyToUserOid = "";
                }
                if (!SharedPreferencesUtil.isEmpty(inReplyToMessageOid)) {
                    // Construct Related "Msg" from available info
                    // and add it recursively
                    JSONObject inReplyToMessage = new JSONObject();
                    inReplyToMessage.put("id_str", inReplyToMessageOid);
                    inReplyToMessage.put("user", inReplyToUser);
                    message.inReplyToMessage = messageFromJson(inReplyToMessage);
                }
            }
        } catch (JSONException e) {
            throw ConnectionException.loggedJsonException(TAG, e, jso, "Parsing message");
        } catch (Exception e) {
            Log.e(TAG, "messageFromJson: " + e.toString());
            e.printStackTrace();
            return MbMessage.getEmpty();
        }
        return message;
    }

    private MbUser userFromJson(JSONObject jso) throws ConnectionException {
        if (jso == null) {
            return MbUser.getEmpty();
        }
        String userName = "";
        if (jso.has("screen_name")) {
            userName = jso.optString("screen_name");
            if (SharedPreferencesUtil.isEmpty(userName)) {
                userName = "";
            }
        }
        MbUser user = MbUser.fromOriginAndUserName(httpConnection.connectionData.originId, userName);
        user.reader = MbUser.fromOriginAndUserName(httpConnection.connectionData.originId, httpConnection.accountUsername);
        if (jso.has("id_str")) {
            user.oid = jso.optString("id_str");
        } else if (jso.has("id")) {
            user.oid = jso.optString("id");
        } 
        if (SharedPreferencesUtil.isEmpty(user.oid)) {
            user.oid = "";
        }
        user.realName = jso.optString("name");
        user.avatarUrl = jso.optString("profile_image_url");
        user.description = jso.optString("description");
        user.homepage = jso.optString("url");
        if (jso.has("created_at")) {
            String createdAt = jso.optString("created_at");
            if (createdAt.length() > 0) {
                user.createdDate = Date.parse(createdAt);
            }
        }
        if (!jso.isNull("following")) {
            user.followedByReader = jso.optBoolean("following");
        }
        if (jso.has("status")) {
            JSONObject latestMessage;
            try {
                latestMessage = jso.getJSONObject("status");
                // This message doesn't have a sender!
                user.latestMessage = messageFromJson(latestMessage);
            } catch (JSONException e) {
                throw ConnectionException.loggedJsonException(TAG, e, jso, "getting status from user");
            }
        }
        return user;
    }
    
    /**
     * @see <a
     *      href="https://dev.twitter.com/docs/api/1.1/get/users/show">GET users/show</a>
     */
    @Override
    public MbUser getUser(String userId) throws ConnectionException {
        Uri sUri = Uri.parse(getApiPath(ApiRoutineEnum.GET_USER));
        Uri.Builder builder = sUri.buildUpon();
        builder.appendQueryParameter("user_id", userId);
        JSONObject jso = httpConnection.getRequest(builder.build().toString());
        return userFromJson(jso);
    }
    
    @Override
    public MbMessage postDirectMessage(String message, String userId) throws ConnectionException {
        List<NameValuePair> formParams = new ArrayList<NameValuePair>();
        formParams.add(new BasicNameValuePair("text", message));
        if ( !TextUtils.isEmpty(userId)) {
            formParams.add(new BasicNameValuePair("user_id", userId));
        }
        JSONObject jso = postRequest(ApiRoutineEnum.POST_DIRECT_MESSAGE, formParams);
        return messageFromJson(jso);
    }
    
    @Override
    public MbMessage postReblog(String rebloggedId) throws ConnectionException {
        JSONObject jso = httpConnection.postRequest(getApiPath(ApiRoutineEnum.POST_REBLOG) + rebloggedId + EXTENSION);
        return messageFromJson(jso);
    }

    /**
     * Check API requests status.
     * 
     * Returns the remaining number of API requests available to the requesting 
     * user before the API limit is reached for the current hour. Calls to 
     * rate_limit_status do not count against the rate limit.  If authentication 
     * credentials are provided, the rate limit status for the authenticating 
     * user is returned.  Otherwise, the rate limit status for the requester's 
     * IP address is returned.
     * @see <a
           href="https://dev.twitter.com/docs/api/1/get/account/rate_limit_status">GET 
           account/rate_limit_status</a>
     * 
     * @return JSONObject
     * @throws ConnectionException
     */
    @Override
    public MbRateLimitStatus rateLimitStatus() throws ConnectionException {
        JSONObject result = httpConnection.getRequest(getApiPath(ApiRoutineEnum.ACCOUNT_RATE_LIMIT_STATUS));
        MbRateLimitStatus status = new MbRateLimitStatus();
        if (result != null) {
            switch (getApi()) {
                case TWITTER1P0:
                case STATUSNET_TWITTER:
                    status.remaining = result.optInt("remaining_hits");
                    status.limit = result.optInt("hourly_limit");
                    break;
                default:
                    JSONObject resources = null;
                    try {
                        resources = result.getJSONObject("resources");
                        JSONObject limitObject = resources.getJSONObject("statuses").getJSONObject("/statuses/home_timeline");
                        status.remaining = limitObject.optInt("remaining");
                        status.limit = limitObject.optInt("limit");
                    } catch (JSONException e) {
                        throw ConnectionException.loggedJsonException(TAG, e, resources, "getting rate limits");
                    }
            }
        }
        return status;
    }
    
    @Override
    public MbMessage updateStatus(String message, String inReplyToId) throws ConnectionException {
        List<NameValuePair> formParams = new ArrayList<NameValuePair>();
        formParams.add(new BasicNameValuePair("status", message));
        
        // This parameter was removed from API:
        // formParams.add(new BasicNameValuePair("source", SOURCE_PARAMETER));
        
        if ( !TextUtils.isEmpty(inReplyToId)) {
            formParams.add(new BasicNameValuePair("in_reply_to_status_id", inReplyToId));
        }
        JSONObject jso = postRequest(ApiRoutineEnum.STATUSES_UPDATE, formParams);
        return messageFromJson(jso);
    }

    /**
     * @see <a
     *      href="http://apiwiki.twitter.com/Twitter-REST-API-Method%3A-account%C2%A0verify_credentials">Twitter
     *      REST API Method: account verify_credentials</a>
     */
    @Override
    public MbUser verifyCredentials() throws ConnectionException {
        JSONObject user = httpConnection.getRequest(getApiPath(ApiRoutineEnum.ACCOUNT_VERIFY_CREDENTIALS));
        return userFromJson(user);
    }

    protected final JSONObject postRequest(ApiRoutineEnum apiRoutine, List<NameValuePair> formParams) throws ConnectionException {
        return httpConnection.postRequest(getApiPath(apiRoutine), formParams);
    }
}
