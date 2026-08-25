package com.visionbank.approval.workflow;

public record Transition(String name, String from, String to, String guard) {}
