/*
 * Copyright (C) 2018 Softbank Robotics Europe
 * See COPYING for the license
 */

package com.softbankrobotics.qisdktutorials.ui.tutorials.motion.attachedframes;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;
import com.aldebaran.qi.sdk.builder.GoToBuilder;
import com.aldebaran.qi.sdk.builder.SayBuilder;
import com.aldebaran.qi.sdk.builder.TransformBuilder;
import com.aldebaran.qi.sdk.object.actuation.AttachedFrame;
import com.aldebaran.qi.sdk.object.actuation.Frame;
import com.aldebaran.qi.sdk.object.actuation.GoTo;
import com.aldebaran.qi.sdk.object.conversation.ConversationStatus;
import com.aldebaran.qi.sdk.object.conversation.Say;
import com.aldebaran.qi.sdk.object.geometry.Transform;
import com.aldebaran.qi.sdk.object.geometry.Vector3;
import com.aldebaran.qi.sdk.object.human.Human;
import com.aldebaran.qi.sdk.object.humanawareness.HumanAwareness;
import com.aldebaran.qi.sdk.util.FutureUtils;
import com.softbankrobotics.qisdktutorials.R;
import com.softbankrobotics.qisdktutorials.ui.conversation.ConversationBinder;
import com.softbankrobotics.qisdktutorials.ui.conversation.ConversationItemType;
import com.softbankrobotics.qisdktutorials.ui.conversation.ConversationView;
import com.softbankrobotics.qisdktutorials.ui.tutorials.TutorialActivity;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class FollowHumanTutorialActivity extends TutorialActivity implements RobotLifecycleCallbacks {

    private static final String TAG = "FollowHumanActivity";

    private ConversationView conversationView;
    private ConversationBinder conversationBinder;
    private Button followButton;
    private Button stopButton;

    // The QiContext provided by the QiSDK.
    private QiContext qiContext;
    // Store the action execution future.
    private Future<Void> goToFuture;
    // Store the GoTo action.
    private GoTo goTo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        conversationView = findViewById(R.id.conversationView);
        followButton = findViewById(R.id.follow_button);
        stopButton = findViewById(R.id.stop_button);

        // Search humans on follow button clicked.
        followButton.setOnClickListener(v -> {
            if (qiContext != null) {
                followButton.setEnabled(false);
                displayLine("Following in 3 seconds...", ConversationItemType.INFO_LOG);
                // Wait 3 seconds before following.
                FutureUtils.wait(3, TimeUnit.SECONDS).andThenConsume(ignored -> searchHumans());
            }
        });

        // Stop moving on stop button clicked.
        stopButton.setOnClickListener(v -> {
            stopButton.setEnabled(false);
            String message = "Stopping...";
            Log.i(TAG, message);
            displayLine(message, ConversationItemType.INFO_LOG);
            stopMoving();
        });

        QiSDK.register(this, this);
    }

    @Override
    protected void onDestroy() {
        QiSDK.unregister(this, this);

        super.onDestroy();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_follow_human_tutorial;
    }

    @Override
    public void onRobotFocusGained(QiContext qiContext) {
        Log.i(TAG, "Focus gained.");
        // Store the provided QiContext.
        this.qiContext = qiContext;

        // Bind the conversational events to the view.
        ConversationStatus conversationStatus = qiContext.getConversation().status(qiContext.getRobotContext());
        conversationBinder = conversationView.bindConversationTo(conversationStatus);

        Say say = SayBuilder.with(qiContext)
                .withText("Press \"Follow\" and I will follow you. Press \"Stop\" to stop me.")
                .build();

        say.run();

        enterWaitingForOrderState();
    }

    @Override
    public void onRobotFocusLost() {
        Log.i(TAG, "Focus lost.");

        if (conversationBinder != null) {
            conversationBinder.unbind();
        }

        // Remove the QiContext.
        this.qiContext = null;
        // Remove on started listeners from the GoTo action.
        if (goTo != null) {
            goTo.removeAllOnStartedListeners();
        }
    }

    @Override
    public void onRobotFocusRefused(String reason) {
        // Nothing here.
    }

    private void enterWaitingForOrderState() {
        String message = "Waiting for order...";
        Log.i(TAG, message);
        displayLine(message, ConversationItemType.INFO_LOG);
        runOnUiThread(() -> {
            stopButton.setEnabled(false);
            followButton.setEnabled(true);
        });
    }

    private void enterMovingState() {
        String message = "Moving...";
        Log.i(TAG, message);
        displayLine(message, ConversationItemType.INFO_LOG);
        runOnUiThread(() -> {
            followButton.setEnabled(false);
            stopButton.setEnabled(true);
        });
    }

    private void searchHumans() {
        HumanAwareness humanAwareness = qiContext.getHumanAwareness();
        Future<List<Human>> humansAroundFuture = humanAwareness.async().getHumansAround();
        humansAroundFuture.andThenConsume(humans -> {
            // If humans found, follow the closest one.
            if (!humans.isEmpty()) {
                Log.i(TAG, "Human found.");
                Human humanToFollow = getClosestHuman(humans);
                followHuman(humanToFollow);
            } else {
                Log.i(TAG, "No human.");
                enterWaitingForOrderState();
            }
        });
    }

    private void followHuman(Human human) {
        // Create the target frame from the human.
        Frame targetFrame = createTargetFrame(human);

        // Create a GoTo action.
        goTo = GoToBuilder.with(qiContext)
                .withFrame(targetFrame)
                .build();

        // Update UI when the GoTo action starts.
        goTo.addOnStartedListener(this::enterMovingState);

        // Execute the GoTo action asynchronously.
        goToFuture = goTo.async().run();

        // Update UI when the GoTo action finishes.
        goToFuture.thenConsume(future -> {
            if (future.isSuccess()) {
                Log.i(TAG, "Target reached.");
                enterWaitingForOrderState();
            } else if (future.isCancelled()) {
                Log.i(TAG, "Movement stopped.");
                enterWaitingForOrderState();
            } else {
                Log.e(TAG, "Movement error.", future.getError());
                enterWaitingForOrderState();
            }
        });
    }

    private Frame createTargetFrame(Human humanToFollow) {
        // Get the human head frame.
        Frame humanFrame = humanToFollow.getHeadFrame();
        // Create a transform for Pepper to stay at 1 meter in front of the human.
        Transform transform = TransformBuilder.create().fromXTranslation(1);
        // Create an AttachedFrame that automatically updates with the human frame.
        AttachedFrame attachedFrame = humanFrame.makeAttachedFrame(transform);
        // Returns the corresponding Frame.
        return attachedFrame.frame();
    }

    private void stopMoving() {
        // Cancel the GoTo action asynchronously.
        if (goToFuture != null) {
            goToFuture.requestCancellation();
        }
    }

    private Human getClosestHuman(List<Human> humans) {
        // Get the robot frame.
        final Frame robotFrame = qiContext.getActuation().robotFrame();

        // Compare humans using the distance.
        Comparator<Human> comparator = (human1, human2) -> Double.compare(getDistance(robotFrame, human1), getDistance(robotFrame, human2));

        // Return the closest human.
        return Collections.min(humans, comparator);
    }

    private double getDistance(Frame robotFrame, Human human) {
        // Get the human head frame.
        Frame humanFrame = human.getHeadFrame();
        // Retrieve the translation between the robot and the human.
        Vector3 translation = humanFrame.computeTransform(robotFrame).getTransform().getTranslation();
        // Get the translation coordinates.
        double x = translation.getX();
        double y = translation.getY();
        // Compute and return the distance.
        return Math.sqrt(x*x + y*y);
    }

    private void displayLine(final String text, final ConversationItemType type) {
        runOnUiThread(() -> conversationView.addLine(text, type));
    }
}
