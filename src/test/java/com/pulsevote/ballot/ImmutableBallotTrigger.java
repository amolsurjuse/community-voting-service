package com.pulsevote.ballot;

import java.sql.Connection;
import java.sql.SQLException;
import org.h2.api.Trigger;

public final class ImmutableBallotTrigger implements Trigger {
    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("accepted ballots are immutable");
    }
}
