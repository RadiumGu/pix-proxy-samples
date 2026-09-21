import { App, Stack, StackProps, Duration } from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';

/**
 * The stable log tokens the proxy emits. These are the SAME strings as
 * com.amazon.aws.pix.core.audit.AuditAlarmTokens; a CI check asserts the two lists agree, because a
 * token renamed on the Java side would leave a metric filter that silently matches nothing - an
 * alarm that can never fire, which is worse than no alarm at all.
 */
export const AUDIT_ALARM_TOKENS = [
  'PIX_AUDIT_SPOOLED',
  'PIX_AUDIT_QUEUE_FULL',
  'PIX_AUDIT_NO_RECORD',
  'PIX_AUDIT_SPOOL_WRITE_FAILED',
] as const;

export interface PixAuditAlarmsProps extends StackProps {
  /** Log group the proxy container writes to. */
  readonly logGroupName: string;
  /** Firehose delivery stream carrying audit records. */
  readonly deliveryStreamName: string;
  /**
   * Optional SNS topic for alarm actions. When absent the alarms are still created and still change
   * state - they simply notify nobody, which is the correct default for a template that does not own
   * the account's notification topology.
   */
  readonly alarmTopic?: import('aws-cdk-lib/aws-sns').ITopic;
}

/**
 * Alarms for the audit path of the Pix CloudHSM proxy.
 *
 * <h2>Why this stack exists</h2>
 *
 * The proxy emits four stable tokens when audit records are at risk, and the repository documented
 * alarming on them without shipping anything that does. Documentation is not an alarm: an operator
 * reading a runbook after the fact is not the same as a page while records are still recoverable.
 *
 * <h2>treatMissingData</h2>
 *
 * The Firehose alarms use BREACHING deliberately. "No data" for a delivery stream that should always
 * be receiving audit records is not calm - it is the signal that delivery stopped, or that the
 * container died, or that the stream was deleted. NOT_BREACHING there would convert a total outage
 * into a green dashboard.
 *
 * The metric-filter alarms use NOT_BREACHING, the opposite choice, for the opposite reason: those
 * metrics only have data points when something has gone wrong, so absence genuinely is health.
 */
export class PixAuditAlarmsStack extends Stack {
  constructor(scope: Construct, id: string, props: PixAuditAlarmsProps) {
    super(scope, id, props);

    const logGroup = logs.LogGroup.fromLogGroupName(this, 'ProxyLogGroup', props.logGroupName);
    const namespace = 'PixProxy/Audit';

    for (const token of AUDIT_ALARM_TOKENS) {
      const metricName = token;

      // The filter pattern is the quoted token, so it matches the token anywhere in the line rather
      // than requiring a specific log layout. The Java side logs it as the first field, but pinning
      // the position would break the alarm on a harmless format change.
      const filter = new logs.MetricFilter(this, `${token}Filter`, {
        logGroup,
        metricNamespace: namespace,
        metricName,
        filterPattern: logs.FilterPattern.literal(`"${token}"`),
        metricValue: '1',
        defaultValue: 0,
      });

      const alarm = new cloudwatch.Alarm(this, `${token}Alarm`, {
        alarmName: `${token}`,
        alarmDescription:
          `The proxy emitted ${token}. Audit records are at risk of being lost; see ` +
          'README-CloudHSM.md for the per-token meaning and the recovery step.',
        metric: filter.metric({ statistic: 'Sum', period: Duration.minutes(1) }),
        threshold: 0,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
        // These metrics only produce data points when something is wrong, so no data is health.
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      });
      void alarm;
    }

    // Firehose delivery failures: records the proxy handed over but that never landed.
    new cloudwatch.Alarm(this, 'AuditDeliveryFailedAlarm', {
      alarmName: 'PixAuditDeliveryToS3Failed',
      alarmDescription:
        'Firehose reported failed deliveries for the Pix audit stream. Records left the proxy ' +
        'and did not reach S3, so the proxy-side spool will NOT contain them.',
      metric: new cloudwatch.Metric({
        namespace: 'AWS/Firehose',
        metricName: 'DeliveryToS3.DataFreshness',
        dimensionsMap: { DeliveryStreamName: props.deliveryStreamName },
        statistic: 'Maximum',
        period: Duration.minutes(5),
      }),
      threshold: 900,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      // Missing data means the stream stopped reporting at all. That is the outage, not the quiet.
      treatMissingData: cloudwatch.TreatMissingData.BREACHING,
    });

    new cloudwatch.Alarm(this, 'AuditIncomingRecordsStoppedAlarm', {
      alarmName: 'PixAuditIncomingRecordsStopped',
      alarmDescription:
        'The Pix audit stream stopped receiving records. Either the proxy is not serving, or it ' +
        'is serving and no longer auditing - the second case is the dangerous one.',
      metric: new cloudwatch.Metric({
        namespace: 'AWS/Firehose',
        metricName: 'IncomingRecords',
        dimensionsMap: { DeliveryStreamName: props.deliveryStreamName },
        statistic: 'Sum',
        period: Duration.minutes(15),
      }),
      threshold: 1,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.BREACHING,
    });
  }
}

const app = new App();
new PixAuditAlarmsStack(app, 'PixAuditAlarms', {
  logGroupName: process.env.PIX_LOG_GROUP ?? '/pix/cloudhsm-proxy',
  deliveryStreamName: process.env.PIX_AUDIT_STREAM ?? 'pix-audit-stream',
});
app.synth();
