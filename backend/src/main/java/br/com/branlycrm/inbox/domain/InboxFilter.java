package br.com.branlycrm.inbox.domain;

import java.util.UUID;

/**
 * Filters of the conversation list; null means any.
 *
 * @param status WAITING or IN_SERVICE
 * @param assigneeId only the conversations this member serves
 * @param unassigned only the conversations nobody serves
 * @param needsHuman only the ones marked (true) or not marked (false)
 */
public record InboxFilter(String status, UUID assigneeId, boolean unassigned, Boolean needsHuman) {}
