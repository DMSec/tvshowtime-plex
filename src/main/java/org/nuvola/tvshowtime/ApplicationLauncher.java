package org.nuvola.tvshowtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.PostConstruct;

import org.nuvola.tvshowtime.business.plex.MediaContainer;
import org.nuvola.tvshowtime.business.plex.Video;
import org.nuvola.tvshowtime.business.tvshowtime.AccessToken;
import org.nuvola.tvshowtime.business.tvshowtime.AuthorizationCode;
import org.nuvola.tvshowtime.business.tvshowtime.Message;
import org.nuvola.tvshowtime.util.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.nuvola.tvshowtime.util.Constants.PMS_WATCH_HISTORY;
import static org.nuvola.tvshowtime.util.Constants.TVST_ACCESS_TOKEN_URI;
import static org.nuvola.tvshowtime.util.Constants.TVST_AUTHORIZE_URI;
import static org.nuvola.tvshowtime.util.Constants.TVST_CHECKIN_URI;
import static org.nuvola.tvshowtime.util.Constants.TVST_CLIENT_ID;
import static org.nuvola.tvshowtime.util.Constants.TVST_CLIENT_SECRET;
import static org.nuvola.tvshowtime.util.Constants.TVST_USER_AGENT;
import static org.springframework.http.HttpMethod.POST;

@SpringBootApplication
public class ApplicationLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationLauncher.class);

    @Value("${nuvola.tvshowtime.tokenFile}")
    private String tokenFile;
    @Value("${nuvola.pms.path}")
    private String pmsHost;

    private RestTemplate tvShowTimeTemplate;
    private RestTemplate pmsTemplate;
    private AccessToken accessToken;
    private Timer tokenTimer;

	public static void main(String[] args) {
		SpringApplication.run(ApplicationLauncher.class, args);
	}

    @PostConstruct
	public void init() {
        tvShowTimeTemplate = new RestTemplate();

        File storeToken = new File(tokenFile);
        if (storeToken.exists()) {
            try {
                FileInputStream fileInputStream = new FileInputStream(storeToken);
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                accessToken = (AccessToken) objectInputStream.readObject();
                objectInputStream.close();
                fileInputStream.close();

                LOG.info("AccessToken loaded from file with success : " + accessToken);
                processWatchedEpisodes();
            } catch (Exception e) {
                LOG.error("Error parsing the AccessToken stored in 'session_token'.");
                LOG.error("Please remove the 'session_token' file, and try again.");
                LOG.error(e.getMessage());
            }
        } else {
            requestAccessToken();
        }
    }

    private void requestAccessToken() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>("client_id=" + TVST_CLIENT_ID, headers);

            ResponseEntity<AuthorizationCode> content = tvShowTimeTemplate.exchange(TVST_AUTHORIZE_URI, POST, entity,
                    AuthorizationCode.class);
            AuthorizationCode authorizationCode = content.getBody();

            if (authorizationCode.getResult().equals("OK")) {
                LOG.info("Linking with your TVShowTime account using the code " + authorizationCode.getDevice_code());
                LOG.info("Please open the URL " + authorizationCode.getVerification_url() + " in your browser");
                LOG.info("Connect with your TVShowTime account and type in the following code : ");
                LOG.info(authorizationCode.getUser_code());

                tokenTimer = new Timer();
                tokenTimer.scheduleAtFixedRate(new TimerTask() {
                    @Override
                    public void run() {
                        loadAccessToken(authorizationCode.getDevice_code());
                    }
                }, 1000 * authorizationCode.getInterval(), 1000 * authorizationCode.getInterval());
            } else {
                LOG.error("OAuth authentication TVShowTime failed.");
            }
        } catch (Exception e) {
            LOG.error("OAuth authentication TVShowTime failed.");
            LOG.error(e.getMessage());
        }
    }

    private void loadAccessToken(String deviceCode) {
        String query = new StringBuilder("client_id=")
                .append(TVST_CLIENT_ID)
                .append("&client_secret=")
                .append(TVST_CLIENT_SECRET)
                .append("&code=")
                .append(deviceCode)
                .toString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<String> entity = new HttpEntity<>(query, headers);

        ResponseEntity<AccessToken> content = tvShowTimeTemplate.exchange(TVST_ACCESS_TOKEN_URI, POST, entity,
                AccessToken.class);
        accessToken = content.getBody();

        if (accessToken.getResult().equals("OK")) {
            LOG.info("AccessToken from TVShowTime with success : " + accessToken);
            tokenTimer.cancel();

            storeAccessToken();
            processWatchedEpisodes();
        } else {
            if (accessToken.getMessage().equals("Authorization pending")
                    || accessToken.getMessage().equals("Slow down")) {
                LOG.info("Still waiting for the user to type in the code in TVShowTime ...");
            } else {
                LOG.error("Unexpected error did arrive, please reload the service :-(");
                tokenTimer.cancel();
            }
        }
    }

    private void storeAccessToken() {
        try {
            File storeToken = new File(tokenFile);
            FileOutputStream fileOutputStream = new FileOutputStream(storeToken);
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
            objectOutputStream.writeObject(accessToken);
            objectOutputStream.close();
            fileOutputStream.close();

            LOG.info("AccessToken store successfully inside a file...");
        } catch (Exception e) {
            LOG.error("Unexpected error did arrive when trying to store the AccessToken in a file ");
            LOG.error(e.getMessage());
        }
    }

    private void processWatchedEpisodes() {
        pmsTemplate = new RestTemplate();
        ResponseEntity<MediaContainer> response =  pmsTemplate.getForEntity(pmsHost + PMS_WATCH_HISTORY,
                MediaContainer.class);
        MediaContainer mediaContainer = response.getBody();

        for (Video video : mediaContainer.getVideo()) {
            LocalDateTime date = DateUtils.getDateTimeFromTimestamp(video.getViewedAt());

            // Mark as watched only today and yesterday episodes
            if((date.toLocalDate().equals(LocalDate.now()) || date.toLocalDate().equals(LocalDate.now().minusDays(1)))
                    && video.getType().equals("episode")) {
                String episode = new StringBuilder(video.getGrandparentTitle())
                        .append(" - S")
                        .append(video.getParentIndex())
                        .append("E").append(video.getIndex())
                        .toString();

                markEpisodeAsWatched(episode);
            }
        }
    }

    private void markEpisodeAsWatched(String episode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add("User-Agent", TVST_USER_AGENT);
        HttpEntity<String> entity = new HttpEntity<>("filename=" + episode, headers);

        String checkinUrl = new StringBuilder(TVST_CHECKIN_URI)
                .append("?access_token=")
                .append(accessToken.getAccess_token())
                .toString();

        ResponseEntity<Message> content = tvShowTimeTemplate.exchange(checkinUrl, POST, entity, Message.class);
        Message message = content.getBody();

        if (message.getResult().equals("OK")) {
            LOG.info("Mark " + episode + " as watched in TVShowTime");
        } else {
            LOG.error("Error while marking [" + episode + "] as watched in TVShowTime ");
        }
    }
}
